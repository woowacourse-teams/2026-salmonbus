package com.gustler.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.TestDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

class MigrationNetworkTimeoutCallbackTest {
    private static final int POOL_TIMEOUT_MILLIS = 1_000;

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
    @ValueSource(strings = {"PT0S", "PT-1S"})
    void 무제한이나_음수_소켓_제한은_받지_않는다(String timeout) {
        assertThatThrownBy(() -> new MigrationNetworkTimeoutCallback(Duration.parse(timeout)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private void sql(String name, String body) throws IOException {
        Files.writeString(migrations.resolve(name), body);
    }

    private FluentConfiguration configure(HikariDataSource pool, Observer observer, MigrationNetworkTimeoutCallback callback) {
        return Flyway.configure().dataSource(pool).locations("filesystem:" + migrations).callbacks(callback, observer);
    }

    private static HikariDataSource pool(TestDatabase database) {
        var config = new HikariConfig();
        config.setJdbcUrl(database.jdbcUrl());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.addDataSourceProperty("socketTimeout", String.valueOf(POOL_TIMEOUT_MILLIS / 1_000));
        return new HikariDataSource(config);
    }

    /**
     * 풀이 지금 빌려줄 수 있는 연결을 한꺼번에 빌려 확인한다. 마이그레이션 연결이 끊기면 Flyway 가 주 연결을 반납하지 못해 그만큼은 빌릴 수 없다.
     */
    private static void assertPoolTimeout(HikariDataSource pool) throws SQLException {
        var borrowed = new ArrayList<Connection>();
        try {
            final int lendable = pool.getMaximumPoolSize() - pool.getHikariPoolMXBean().getActiveConnections();
            for (int i = 0; i < lendable; i++) {
                borrowed.add(pool.getConnection());
            }
            assertThat(borrowed).isNotEmpty().allSatisfy(connection ->
                assertThat(connection.getNetworkTimeout()).isEqualTo(POOL_TIMEOUT_MILLIS));
        } finally {
            for (var connection : borrowed) {
                connection.close();
            }
        }
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
