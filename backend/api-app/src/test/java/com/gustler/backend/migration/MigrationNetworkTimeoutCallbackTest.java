package com.gustler.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gustler.backend.support.TestDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class MigrationNetworkTimeoutCallbackTest {
    private static final int POOL_TIMEOUT_MILLIS = 1_000;
    private static final Pattern STATEMENT_TIMEOUT = Pattern.compile("statement_timeout", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATEMENT_TIMEOUT_SETTING = Pattern.compile(
        "SET\\s+(?:LOCAL\\s+|SESSION\\s+)?statement_timeout\\s*(?:=|TO)\\s*('[^']*'|[^\\s;]+)"
            + "|set_config\\s*\\(\\s*'statement_timeout'\\s*,\\s*('[^']*'|[^\\s,)]+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_VALUE = Pattern.compile("(\\d+)\\s*(us|ms|s|min|h|d)?");

    @TempDir
    Path migrations;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 풀_소켓_제한보다_오래_걸리는_마이그레이션_문장도_끝까지_실행한다(final boolean group) throws Exception {
        sql("V1__sleep.sql", "SELECT pg_sleep(1.5);");
        try (var database = TestDatabase.create(); var pool = pool(database)) {
            var observer = new Observer();
            var during = new ArrayList<Integer>();
            var other = new ArrayList<Integer>();
            var flyway = configure(pool, observer, new MigrationNetworkTimeoutCallback(Duration.ofSeconds(3)))
                .group(group)
                .javaMigrations(new Probe("2", migration -> {
                    during.add(migration.getNetworkTimeout());
                    try (var connection = pool.getConnection()) {
                        other.add(connection.getNetworkTimeout());
                    }
                }))
                .load();

            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(observer.seen).containsExactly(
                "beforeEachMigrate=3000", "afterEachMigrate=1000",
                "beforeEachMigrate=3000", "afterEachMigrate=1000");
            assertThat(during).containsExactly(3_000);
            assertThat(other).containsExactly(POOL_TIMEOUT_MILLIS);
            assertPoolTimeout(pool);
        }
    }

    @Test
    void 콜백이_없으면_같은_문장이_풀_소켓_제한에서_끊긴다() throws Exception {
        sql("V1__sleep.sql", "SELECT pg_sleep(1.5);");
        try (var database = TestDatabase.create(); var pool = pool(database)) {
            var flyway = Flyway.configure().dataSource(pool).locations("filesystem:" + migrations).load();

            assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void 마이그레이션이_실패하면_원래_오류를_남기고_소켓_제한을_되돌린다() throws Exception {
        sql("V1__fail.sql", "SELECT pg_sleep(1.5);\nSELECT 1 / 0;");
        try (var database = TestDatabase.create(); var pool = pool(database)) {
            var observer = new Observer();
            var flyway = configure(pool, observer, new MigrationNetworkTimeoutCallback(Duration.ofSeconds(3))).load();

            assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .rootCause().hasMessageContaining("division by zero");
            assertThat(observer.seen).containsExactly("beforeEachMigrate=3000", "afterEachMigrateError=1000");
            assertPoolTimeout(pool);
        }
    }

    @Test
    void 마이그레이션_소켓_제한을_넘긴_문장은_끊고_풀에는_남기지_않는다() throws Exception {
        sql("V1__sleep.sql", "SELECT pg_sleep(3);");
        try (var database = TestDatabase.create(); var pool = pool(database)) {
            var observer = new Observer();
            var flyway = configure(pool, observer, new MigrationNetworkTimeoutCallback(Duration.ofSeconds(2))).load();

            assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
            assertThat(observer.seen).containsExactly("beforeEachMigrate=2000", "afterEachMigrateError=closed");
            assertPoolTimeout(pool);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5})
    void 원래_소켓_제한이_무제한이거나_설정값보다_길면_줄이지_않는다(final int poolSeconds) throws Exception {
        final int poolMillis = poolSeconds * 1_000;
        sql("V1__sleep.sql", "SELECT pg_sleep(3);");
        try (var database = TestDatabase.create(); var pool = pool(database, poolSeconds)) {
            var observer = new Observer();
            var during = new ArrayList<Integer>();
            var flyway = configure(pool, observer, new MigrationNetworkTimeoutCallback(Duration.ofSeconds(2)))
                .javaMigrations(new Probe("2", migration -> during.add(migration.getNetworkTimeout())))
                .load();

            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(observer.seen).containsExactly(
                "beforeEachMigrate=" + poolMillis, "afterEachMigrate=" + poolMillis,
                "beforeEachMigrate=" + poolMillis, "afterEachMigrate=" + poolMillis);
            assertThat(during).containsExactly(poolMillis);
            assertPoolTimeout(pool, poolMillis);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5})
    void 줄이지_않은_연결은_마이그레이션이_실패해도_원래_소켓_제한을_유지한다(final int poolSeconds) throws Exception {
        final int poolMillis = poolSeconds * 1_000;
        sql("V1__fail.sql", "SELECT 1 / 0;");
        try (var database = TestDatabase.create(); var pool = pool(database, poolSeconds)) {
            var observer = new Observer();
            var flyway = configure(pool, observer, new MigrationNetworkTimeoutCallback(Duration.ofSeconds(2))).load();

            assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .rootCause().hasMessageContaining("division by zero");
            assertThat(observer.seen).containsExactly(
                "beforeEachMigrate=" + poolMillis, "afterEachMigrateError=" + poolMillis);
            assertPoolTimeout(pool, poolMillis);
        }
    }

    @Test
    void 소켓_제한을_늘리지_못하면_마이그레이션을_멈추고_되돌리지_않는다() throws Exception {
        var connection = mock(Connection.class);
        given(connection.getNetworkTimeout()).willReturn(POOL_TIMEOUT_MILLIS);
        willThrow(new SQLException("closed")).given(connection).setNetworkTimeout(any(Executor.class), anyInt());
        var context = context(connection);
        var callback = new MigrationNetworkTimeoutCallback(Duration.ofSeconds(3));

        assertThatThrownBy(() -> callback.handle(Event.BEFORE_EACH_MIGRATE, context))
            .isInstanceOf(FlywayException.class)
            .hasCauseInstanceOf(SQLException.class);
        callback.handle(Event.AFTER_EACH_MIGRATE_ERROR, context);

        verify(connection, times(1)).setNetworkTimeout(any(Executor.class), anyInt());
    }

    @Test
    void 성공한_마이그레이션의_소켓_제한을_되돌리지_못하면_멈춘다() throws Exception {
        var connection = restoreFailing();
        var context = context(connection);
        var callback = new MigrationNetworkTimeoutCallback(Duration.ofSeconds(3));
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);

        assertThatThrownBy(() -> callback.handle(Event.AFTER_EACH_MIGRATE, context))
            .isInstanceOf(FlywayException.class)
            .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void 실패한_마이그레이션의_소켓_제한을_되돌리지_못해도_원래_오류를_가리지_않는다() throws Exception {
        var connection = restoreFailing();
        var context = context(connection);
        var callback = new MigrationNetworkTimeoutCallback(Duration.ofSeconds(3));
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);

        assertThatCode(() -> callback.handle(Event.AFTER_EACH_MIGRATE_ERROR, context)).doesNotThrowAnyException();
        verify(connection).setNetworkTimeout(any(Executor.class), eq(POOL_TIMEOUT_MILLIS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S"})
    void 무제한이나_음수_소켓_제한은_받지_않는다(String timeout) {
        assertThatThrownBy(() -> new MigrationNetworkTimeoutCallback(Duration.parse(timeout)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 마이그레이션의_statement_timeout은_마이그레이션_연결_소켓_제한보다_짧다() throws IOException {
        var networkTimeout = configuredNetworkTimeout();

        var actual = statementTimeouts();

        assertThat(actual).isNotEmpty();
        actual.forEach((file, timeouts) -> assertThat(timeouts).as(file).allSatisfy(timeout ->
            assertThat(timeout).isPositive().isLessThan(networkTimeout)));
    }

    private void sql(String name, String body) throws IOException {
        Files.writeString(migrations.resolve(name), body);
    }

    /**
     * API 가 실제로 읽는 설정 파일의 값이다. 상수로 따로 두면 설정만 바뀌었을 때 이 검사가 어긋난다.
     */
    private static Duration configuredNetworkTimeout() throws IOException {
        var sources = new YamlPropertySourceLoader()
            .load("application", new FileSystemResource("src/main/resources/application.yml"));
        return new Binder(ConfigurationPropertySources.from(sources))
            .bind("migration.network-timeout", Duration.class)
            .get();
    }

    /**
     * API 의 Flyway 가 읽는 위치의 마이그레이션에서 statement_timeout 설정을 파일별로 모은다.
     * 해석하지 못한 표기가 있으면 그 파일에서 실패한다. 건너뛰면 새 표기의 긴 값이 이 검사를 빠져나간다.
     */
    private static Map<String, List<Duration>> statementTimeouts() throws IOException {
        var found = new TreeMap<String, List<Duration>>();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql")) {
            var sql = withoutComments(resource.getContentAsString(StandardCharsets.UTF_8));
            var timeouts = STATEMENT_TIMEOUT_SETTING.matcher(sql).results()
                .map(setting -> duration(resource.getFilename(), setting))
                .toList();
            assertThat(timeouts)
                .as("%s 의 statement_timeout 을 모두 해석해야 한다", resource.getFilename())
                .hasSize((int) STATEMENT_TIMEOUT.matcher(sql).results().count());
            if (!timeouts.isEmpty()) {
                found.put(resource.getFilename(), timeouts);
            }
        }
        return found;
    }

    private static String withoutComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    }

    /**
     * PostgreSQL 의 시간 단위를 따른다. 단위가 없으면 statement_timeout 의 기본 단위인 밀리초다.
     */
    private static Duration duration(String file, MatchResult setting) {
        var raw = (setting.group(1) != null ? setting.group(1) : setting.group(2)).replace("'", "").strip();
        var value = TIME_VALUE.matcher(raw);
        if (!value.matches()) {
            return fail("%s 의 statement_timeout 값을 해석할 수 없다: %s", file, raw);
        }
        final long amount = Long.parseLong(value.group(1));
        var unit = value.group(2) == null ? "ms" : value.group(2);
        return switch (unit) {
            case "us" -> Duration.ofNanos(amount * 1_000);
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "min" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
    }

    private FluentConfiguration configure(HikariDataSource pool, Observer observer, MigrationNetworkTimeoutCallback callback) {
        return Flyway.configure().dataSource(pool).locations("filesystem:" + migrations).callbacks(callback, observer);
    }

    private static HikariDataSource pool(TestDatabase database) {
        return pool(database, POOL_TIMEOUT_MILLIS / 1_000);
    }

    private static HikariDataSource pool(TestDatabase database, final int socketTimeoutSeconds) {
        var config = new HikariConfig();
        config.setJdbcUrl(database.jdbcUrl());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.addDataSourceProperty("socketTimeout", String.valueOf(socketTimeoutSeconds));
        return new HikariDataSource(config);
    }

    /**
     * 풀이 지금 빌려줄 수 있는 연결을 한꺼번에 빌려 확인한다. 마이그레이션 연결이 끊기면 Flyway 가 주 연결을 반납하지 못해 그만큼은 빌릴 수 없다.
     */
    private static void assertPoolTimeout(HikariDataSource pool) throws SQLException {
        assertPoolTimeout(pool, POOL_TIMEOUT_MILLIS);
    }

    private static void assertPoolTimeout(HikariDataSource pool, final int expectedMillis) throws SQLException {
        var borrowed = new ArrayList<Connection>();
        try {
            final int lendable = pool.getMaximumPoolSize() - pool.getHikariPoolMXBean().getActiveConnections();
            for (int i = 0; i < lendable; i++) {
                borrowed.add(pool.getConnection());
            }
            assertThat(borrowed).isNotEmpty().allSatisfy(connection ->
                assertThat(connection.getNetworkTimeout()).isEqualTo(expectedMillis));
        } finally {
            for (var connection : borrowed) {
                connection.close();
            }
        }
    }

    private static Connection restoreFailing() throws SQLException {
        var connection = mock(Connection.class);
        given(connection.getNetworkTimeout()).willReturn(POOL_TIMEOUT_MILLIS);
        willThrow(new SQLException("closed")).given(connection)
            .setNetworkTimeout(any(Executor.class), eq(POOL_TIMEOUT_MILLIS));
        return connection;
    }

    private static org.flywaydb.core.api.callback.Context context(Connection connection) {
        var context = mock(org.flywaydb.core.api.callback.Context.class);
        given(context.getConnection()).willReturn(connection);
        return context;
    }

    /**
     * 콜백 이름 순으로 실행되므로 시험하는 콜백 뒤에서 마이그레이션 연결의 소켓 제한을 읽는다.
     */
    private static final class Observer implements Callback {
        private final List<String> seen = new ArrayList<>();

        @Override
        public boolean supports(Event event, org.flywaydb.core.api.callback.Context context) {
            return event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE
                || event == Event.AFTER_EACH_MIGRATE_ERROR;
        }

        @Override
        public boolean canHandleInTransaction(Event event, org.flywaydb.core.api.callback.Context context) {
            return true;
        }

        @Override
        public void handle(Event event, org.flywaydb.core.api.callback.Context context) {
            try {
                var connection = context.getConnection();
                seen.add(event.getId() + "=" + (connection.isClosed() ? "closed" : connection.getNetworkTimeout()));
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public String getCallbackName() {
            return "zzNetworkTimeoutObserver";
        }
    }

    private interface DuringMigration {
        void run(Connection connection) throws SQLException;
    }

    private static final class Probe extends BaseJavaMigration {
        private final String version;
        private final DuringMigration during;

        private Probe(String version, DuringMigration during) {
            this.version = version;
            this.during = during;
        }

        @Override
        protected void init() {
        }

        @Override
        public MigrationVersion getVersion() {
            return MigrationVersion.fromVersion(version);
        }

        @Override
        public String getDescription() {
            return "Network timeout probe";
        }

        @Override
        public void migrate(Context context) throws Exception {
            during.run(context.getConnection());
        }
    }
}
