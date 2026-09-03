package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LivePendingForecastIndexPlanTest extends PostgresMigrationTestSupport {

    @BeforeAll
    static void migrateHistoricalSchema() {
        new HistoricalSchema().migrate(database);
    }

    @Test
    void genericPreparedPlanUsesTheLivePendingPartialIndexAfterSixExecutions() throws Exception {
        try (Connection connection = connection()) {
            long routeVersionId = insertFixture(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ANALYZE observation_batch");
                statement.execute("SET plan_cache_mode = force_generic_plan");
                statement.execute("""
                    PREPARE live_pending(bigint, integer) AS
                    SELECT id, route_version_id, response_received_at
                    FROM observation_batch
                    WHERE route_version_id = $1
                      AND ingestion_origin = 'LIVE'
                      AND forecast_completed_at IS NULL
                      AND response_received_at IS NOT NULL
                      AND outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY')
                    ORDER BY response_received_at
                    LIMIT $2
                    """);
                for (int execution = 0; execution < 6; execution++) {
                    statement.execute("EXECUTE live_pending(" + routeVersionId + ", 20)");
                }
                StringBuilder plan = new StringBuilder();
                try (ResultSet rows = statement.executeQuery(
                    "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE live_pending("
                        + routeVersionId + ", 20)")) {
                    while (rows.next()) {
                        plan.append(rows.getString(1)).append('\n');
                    }
                }
                String text = plan.toString();
                System.out.println("LIVE_PENDING_GENERIC_PLAN\n" + text);
                assertThat(text).contains("ix_batch_live_awaiting_forecast");
            }
        }
    }

    private static long insertFixture(
        Connection connection
    ) throws Exception {
        long routeId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route (
                public_route_id, source_id, source_route_id, display_name,
                start_stop_name, end_stop_name)
            VALUES ('920000001', 'GBIS', '920000001', 'index-plan', 'start', 'end')
            RETURNING id
            """); ResultSet rows = statement.executeQuery()) {
            rows.next();
            routeId = rows.getLong(1);
        }
        long routeVersionId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route_version (route_id, content_digest, valid_from)
            VALUES (?, ?, '2000-01-01T00:00:00Z') RETURNING id
            """)) {
            statement.setLong(1, routeId);
            statement.setString(2, "8".repeat(64));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                routeVersionId = rows.getLong(1);
            }
        }
        UUID importId = UUID.fromString("00000000-0000-4000-8000-000000000921");
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO historical_import_batch (
                id, manifest_sha256, archive_schema_version, archive_kind, inventory_sha256,
                source_cutoff_at, importer_version, target_kind, target_authority_from_min,
                route_validity_policy, status, expected_batch_count, expected_observation_count)
            VALUES (?, ?, 'fixture', 'BASE', ?, now(), 'fixture', 'LOCAL', now(),
                    'EXTEND_EXACT_CURRENT_VERSION', 'COMPLETE', 20000, 0)
            """)) {
            statement.setObject(1, importId);
            statement.setString(2, "6".repeat(64));
            statement.setString(3, "7".repeat(64));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, outcome, provider_rows, stored_rows, excluded_rows,
                normalization_version, collection_strategy_version, ingestion_origin,
                historical_import_batch_id, semantic_batch_digest, normalized_record_sha256)
            SELECT ?, now() - make_interval(secs => value), 1, 'backfill-' || value,
                   now() - make_interval(secs => value), now() - make_interval(secs => value),
                   'SUCCESS_EMPTY', 0, 0, 0, 'normalization-v1.0.0', 'adaptive-kst-v1.0.1',
                   'S3_BACKFILL', ?, lpad(to_hex(value), 64, '0'), lpad(to_hex(value + 30000), 64, '0')
            FROM generate_series(1, 20000) AS value
            """)) {
            statement.setLong(1, routeVersionId);
            statement.setObject(2, importId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, outcome, provider_rows, stored_rows, excluded_rows,
                normalization_version, collection_strategy_version)
            SELECT ?, now() + make_interval(secs => value), 1, 'live-' || value,
                   now(), now() + make_interval(secs => value), 'SUCCESS_EMPTY', 0, 0, 0,
                   'normalization-v1.0.0', 'adaptive-kst-v1.0.1'
            FROM generate_series(1, 10) AS value
            """)) {
            statement.setLong(1, routeVersionId);
            statement.executeUpdate();
        }
        return routeVersionId;
    }
}
