package com.gustler.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.TestDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QualityRetirementMigrationCallbackTest {
    @ParameterizedTest
    @CsvSource({"false,on,on", "true,off,on", "true,on,off"})
    void V66에만_적용하고_후속_마이그레이션과_다른_연결은_기존_설정을_쓴다(boolean group, String hash, String merge) throws Exception {
        try (var database = TestDatabase.create(); var pool = pool(database, hash, merge)) {
            var observed = new ArrayList<List<String>>();
            var otherConnections = new ArrayList<List<String>>();
            Flyway.configure().dataSource(pool).locations(new String[0]).group(group)
                .callbacks(new QualityRetirementMigrationCallback())
                .javaMigrations(new Probe("65", observed, null, false), new Probe("66", observed, () -> {
                    try (var other = pool.getConnection()) {
                        otherConnections.add(settings(other));
                    }
                }, false), new Probe("67", observed, null, false))
                .load().migrate();
            assertThat(observed).containsExactly(List.of(hash, merge), List.of("off", "off"), List.of(hash, merge));
            assertThat(otherConnections).containsExactly(List.of(hash, merge));
            assertPoolSettings(pool, hash, merge);
        }
    }

    @Test
    void V66이_실패해도_풀에_반납된_연결에_임시_설정을_남기지_않는다() throws Exception {
        try (var database = TestDatabase.create(); var pool = pool(database, "on", "on")) {
            var observed = new ArrayList<List<String>>();
            var flyway = Flyway.configure().dataSource(pool).locations(new String[0])
                .callbacks(new QualityRetirementMigrationCallback())
                .javaMigrations(new Probe("65", observed, null, false), new Probe("66", observed, null, true))
                .load();
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
            assertThat(observed).containsExactly(List.of("on", "on"), List.of("off", "off"));
            assertPoolSettings(pool, "on", "on");
        }
    }

    @Test
    void 기존_SQL의_체크섬을_유지한_채_V65에서_V66으로_전환한다() throws Exception {
        try (var database = TestDatabase.create(); var pool = pool(database, "on", "on")) {
            Flyway.configure().dataSource(pool).target("65").load().migrate();
            var flyway = Flyway.configure().dataSource(pool).target("66")
                .callbacks(new QualityRetirementMigrationCallback()).load();
            assertThat(flyway.migrate().migrationsExecuted).isOne();
            assertThat(flyway.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("66"));
            flyway.validate();
            assertPoolSettings(pool, "on", "on");
        }
    }

    private static HikariDataSource pool(TestDatabase database, String hash, String merge) {
        var config = new HikariConfig();
        config.setJdbcUrl(database.jdbcUrl());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setConnectionInitSql("SET enable_hashjoin=" + hash + "; SET enable_mergejoin=" + merge);
        return new HikariDataSource(config);
    }

    private static void assertPoolSettings(HikariDataSource pool, String hash, String merge) throws SQLException {
        try (var first = pool.getConnection(); var second = pool.getConnection(); var third = pool.getConnection()) {
            for (var connection : List.of(first, second, third)) {
                assertThat(settings(connection)).containsExactly(hash, merge);
            }
        }
    }

    private static List<String> settings(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT current_setting('enable_hashjoin'), current_setting('enable_mergejoin')")) {
            rows.next();
            return List.of(rows.getString(1), rows.getString(2));
        }
    }

    private interface DuringMigration {
        void run() throws SQLException;
    }

    private static final class Probe extends BaseJavaMigration {
        private final String version;
        private final List<List<String>> observed;
        private final DuringMigration during;
        private final boolean fail;

        private Probe(String version, List<List<String>> observed, DuringMigration during, boolean fail) {
            this.version = version;
            this.observed = observed;
            this.during = during;
            this.fail = fail;
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
            return "Query plan scope probe";
        }

        @Override
        public void migrate(Context context) throws Exception {
            observed.add(settings(context.getConnection()));
            if (during != null) {
                during.run();
            }
            if (fail) {
                try (var statement = context.getConnection().createStatement()) {
                    statement.execute("SELECT 1 / 0");
                }
            }
        }
    }
}
