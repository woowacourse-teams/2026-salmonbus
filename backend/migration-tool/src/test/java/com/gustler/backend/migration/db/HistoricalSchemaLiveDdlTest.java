package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HistoricalSchemaLiveDdlTest extends PostgresMigrationTestSupport {

    @Test
    void populatedTableIsPreservedAndLockTimeoutFailsFastBeforeRetry() throws Exception {
        long routeVersionId;
        try (Connection connection = connection()) {
            routeVersionId = insertRoute(connection);
            insertBatch(connection, routeVersionId);
        }
        try (Connection blocker = connection(); PreparedStatement lock = blocker.prepareStatement(
            "LOCK TABLE observation_batch IN ACCESS EXCLUSIVE MODE")) {
            blocker.setAutoCommit(false);
            lock.execute();
            Instant started = Instant.now();
            assertThatThrownBy(() -> new HistoricalSchema().migrate(database, 200, 5))
                .isInstanceOf(RuntimeException.class);
            assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(2));
            blocker.rollback();
        }

        new HistoricalSchema().migrate(database, 2_000, 30);
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*), min(ingestion_origin) FROM observation_batch WHERE route_version_id = ?
            """)) {
            statement.setLong(1, routeVersionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getLong(1)).isOne();
                assertThat(rows.getString(2)).isEqualTo("LIVE");
            }
        }
    }

    private static long insertRoute(
        Connection connection
    ) throws Exception {
        long routeId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route (
                public_route_id, source_id, source_route_id, display_name,
                start_stop_name, end_stop_name)
            VALUES ('930000001', 'GBIS', '930000001', 'ddl-live', 'start', 'end') RETURNING id
            """); ResultSet rows = statement.executeQuery()) {
            rows.next();
            routeId = rows.getLong(1);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route_version (route_id, content_digest, valid_from)
            VALUES (?, ?, now()) RETURNING id
            """)) {
            statement.setLong(1, routeId);
            statement.setString(2, "3".repeat(64));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void insertBatch(
        Connection connection,
        long routeVersionId
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, outcome, provider_rows, stored_rows, excluded_rows,
                normalization_version, collection_strategy_version)
            VALUES (?, now(), 1, 'before-historical-ddl', now(), now(), 'SUCCESS_EMPTY',
                    0, 0, 0, 'normalization-v1.0.0', 'adaptive-kst-v1.0.1')
            """)) {
            statement.setLong(1, routeVersionId);
            statement.executeUpdate();
        }
    }
}
