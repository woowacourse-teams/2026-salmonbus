package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HistoricalSchemaTest extends PostgresMigrationTestSupport {

    @BeforeAll
    static void migrateHistoricalSchema() {
        new HistoricalSchema().migrate(database);
    }

    @Test
    void additiveSchemaAppliesWithoutChangingApplicationFlywayHistory() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT count(*) FROM flyway_schema_history WHERE success")).isEqualTo(12);
            assertThat(count(statement,
                "SELECT count(*) FROM historical_import_schema_history WHERE success AND type = 'SQL'"))
                .isEqualTo(3);
            assertThat(count(statement,
                "SELECT count(*) FROM training_model_release_exclusion WHERE classification='TEMPORARY_RELEASE'"))
                .isEqualTo(1);
            assertThat(count(statement,
                "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema='public' AND table_name='stop_demand_seed_import'"))
                .isEqualTo(1);
        }
    }

    @Test
    void existingAndFutureOrdinaryBatchesDefaultToLiveOrigin() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("""
                SELECT column_default, is_nullable
                FROM information_schema.columns
                WHERE table_schema='public' AND table_name='observation_batch'
                  AND column_name='ingestion_origin'
                """)) {
                rows.next();
                assertThat(rows.getString("column_default")).contains("LIVE");
                assertThat(rows.getString("is_nullable")).isEqualTo("NO");
            }
        }
    }

    private static long count(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
