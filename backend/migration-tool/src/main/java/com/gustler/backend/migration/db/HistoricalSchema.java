package com.gustler.backend.migration.db;

import com.gustler.backend.migration.DatabaseEnvironment;
import com.gustler.backend.migration.MigrationException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;

public final class HistoricalSchema {

    public static final String HISTORY_TABLE = "historical_import_schema_history";
    private static final String LOCATION = "classpath:db/historical-migration";
    private static final List<Integer> REQUIRED_APPLICATION_VERSIONS =
        List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

    public MigrateResult migrate(
        DatabaseEnvironment database
    ) {
        return migrate(database, 2_000, 30);
    }

    public MigrateResult migrate(
        DatabaseEnvironment database,
        int lockTimeoutMillis,
        int statementTimeoutSeconds
    ) {
        requireApplicationSchema(database);
        return flyway(database, lockTimeoutMillis, statementTimeoutSeconds).migrate();
    }

    public void requireCurrent(
        DatabaseEnvironment database
    ) {
        requireApplicationSchema(database);
        if (flyway(database, 2_000, 30).info().pending().length != 0) {
            throw new MigrationException("HISTORICAL_SCHEMA_MIGRATION_PENDING");
        }
    }

    private static Flyway flyway(
        DatabaseEnvironment database,
        int lockTimeoutMillis,
        int statementTimeoutSeconds
    ) {
        return Flyway.configure()
            .dataSource(database.jdbcUrl(), database.username(), database.password())
            .locations(LOCATION)
            .table(HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .validateMigrationNaming(true)
            .initSql("SELECT set_config('lock_timeout', '" + lockTimeoutMillis
                + "ms', false), set_config('statement_timeout', '" + statementTimeoutSeconds
                + "s', false)")
            .load();
    }

    private static void requireApplicationSchema(
        DatabaseEnvironment database
    ) {
        try {
            Flyway.configure()
                .dataSource(database.jdbcUrl(), database.username(), database.password())
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .load()
                .validate();
        } catch (RuntimeException e) {
            throw new MigrationException("APPLICATION_FLYWAY_BASELINE_MISMATCH", e);
        }
        try (Connection connection = DatabaseConnections.open(database);
            PreparedStatement statement = connection.prepareStatement("""
                SELECT version, success
                FROM flyway_schema_history
                WHERE version IS NOT NULL
                ORDER BY installed_rank
                """);
            ResultSet rows = statement.executeQuery()) {
            List<Integer> versions = new ArrayList<>();
            while (rows.next()) {
                if (!rows.getBoolean("success")) {
                    throw new MigrationException("APPLICATION_FLYWAY_MIGRATION_FAILED");
                }
                versions.add(Integer.valueOf(rows.getString("version")));
            }
            if (!REQUIRED_APPLICATION_VERSIONS.equals(versions)) {
                throw new MigrationException("APPLICATION_FLYWAY_BASELINE_MISMATCH");
            }
        } catch (SQLException | NumberFormatException e) {
            throw new MigrationException("APPLICATION_FLYWAY_BASELINE_UNREADABLE", e);
        }
    }
}
