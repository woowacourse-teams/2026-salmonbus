package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.PendingForecastBatch;
import com.gustler.backend.processor.persistence.jdbc.JdbcVehicleTrajectoryRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

class IngestionOriginPositiveCacheTest extends PostgresMigrationTestSupport {

    @Test
    void detectsColumnAddedAfterAnEarlierNegativeLookupAndThenExcludesBackfill() throws Exception {
        long routeVersionId;
        long liveBatchId;
        try (Connection connection = connection()) {
            routeVersionId = insertRouteVersion(connection);
            liveBatchId = insertLiveBatch(connection, routeVersionId);
        }
        JdbcVehicleTrajectoryRepository repository = repository();

        assertThat(repository.findBatchesAwaitingForecast(routeVersionId, 10))
            .extracting(PendingForecastBatch::observationBatchId)
            .containsExactly(liveBatchId);

        new HistoricalSchema().migrate(database);
        try (Connection connection = connection()) {
            insertBackfillBatch(connection, routeVersionId);
        }

        assertThat(repository.findBatchesAwaitingForecast(routeVersionId, 10))
            .extracting(PendingForecastBatch::observationBatchId)
            .containsExactly(liveBatchId);
        assertThat(repository.findBatchesAwaitingForecast(routeVersionId, 10))
            .extracting(PendingForecastBatch::observationBatchId)
            .containsExactly(liveBatchId);
    }

    private static JdbcVehicleTrajectoryRepository repository() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return new JdbcVehicleTrajectoryRepository(JdbcClient.create(dataSource));
    }

    private static long insertRouteVersion(Connection connection) throws Exception {
        long routeId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route (
                public_route_id, source_id, source_route_id, display_name,
                start_stop_name, end_stop_name)
            VALUES ('900000001', 'GBIS', '900000001', '3330', 'start', 'end')
            RETURNING id
            """); ResultSet rows = statement.executeQuery()) {
            rows.next();
            routeId = rows.getLong(1);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route_version (route_id, content_digest, valid_from)
            VALUES (?, ?, '2000-01-01T00:00:00Z')
            RETURNING id
            """)) {
            statement.setLong(1, routeId);
            statement.setString(2, "1".repeat(64));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long insertLiveBatch(Connection connection, long routeVersionId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, outcome, provider_rows, stored_rows, excluded_rows,
                normalization_version, collection_strategy_version)
            VALUES (?, '2000-01-01T00:00:00Z', 1, 'live-before-schema',
                    '2000-01-01T00:00:00Z', '2000-01-01T00:00:01Z',
                    'SUCCESS_EMPTY', 0, 0, 0, 'normalization-v1.0.0',
                    'adaptive-kst-v1.0.1')
            RETURNING id
            """)) {
            statement.setLong(1, routeVersionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void insertBackfillBatch(Connection connection, long routeVersionId) throws Exception {
        UUID importId = UUID.fromString("00000000-0000-4000-8000-000000000099");
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO historical_import_batch (
                id, manifest_sha256, archive_schema_version, archive_kind, inventory_sha256,
                source_cutoff_at, importer_version, target_kind, target_authority_from_min,
                route_validity_policy, status, expected_batch_count, expected_observation_count)
            VALUES (?, ?, 'fixture', 'BASE', ?, '2000-01-01T00:00:02Z', 'fixture', 'LOCAL',
                    '2000-01-01T00:00:01Z', 'EXTEND_EXACT_CURRENT_VERSION', 'STAGING', 1, 0)
            """)) {
            statement.setObject(1, importId);
            statement.setString(2, "2".repeat(64));
            statement.setString(3, "3".repeat(64));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, outcome, provider_rows, stored_rows, excluded_rows,
                normalization_version, collection_strategy_version, ingestion_origin,
                historical_import_batch_id, semantic_batch_digest, normalized_record_sha256)
            VALUES (?, '2000-01-01T00:00:01Z', 1, ?, '2000-01-01T00:00:01Z',
                    '2000-01-01T00:00:02Z', 'SUCCESS_EMPTY', 0, 0, 0,
                    'normalization-v1.0.0-s3-backfill', 'adaptive-kst-v1.2.0',
                    'S3_BACKFILL', ?, ?, ?)
            """)) {
            String digest = "4".repeat(64);
            statement.setLong(1, routeVersionId);
            statement.setString(2, "s3v1:" + digest);
            statement.setObject(3, importId);
            statement.setString(4, digest);
            statement.setString(5, "5".repeat(64));
            statement.executeUpdate();
        }
    }
}
