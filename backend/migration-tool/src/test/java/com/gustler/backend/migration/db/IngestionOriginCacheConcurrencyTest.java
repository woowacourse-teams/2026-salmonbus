package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.processor.PendingForecastBatch;
import com.gustler.backend.processor.persistence.jdbc.JdbcVehicleTrajectoryRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

class IngestionOriginCacheConcurrencyTest extends PostgresMigrationTestSupport {

    @Test
    void concurrentPollingTransitionsToPositiveCacheAndNeverFallsBackAfterColumnRemoval() throws Exception {
        long routeVersionId;
        long liveBatchId;
        try (Connection connection = connection()) {
            routeVersionId = insertRouteVersion(connection);
            liveBatchId = insertLiveBatch(connection, routeVersionId);
        }
        JdbcVehicleTrajectoryRepository repository = repository();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            ArrayList<Future<?>> polls = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                polls.add(executor.submit(() -> {
                    start.await();
                    for (int attempt = 0; attempt < 20; attempt++) {
                        repository.findBatchesAwaitingForecast(routeVersionId, 10);
                    }
                    return null;
                }));
            }
            start.countDown();
            new HistoricalSchema().migrate(database);
            try (Connection connection = connection()) {
                insertBackfillBatch(connection, routeVersionId);
            }
            for (Future<?> poll : polls) {
                poll.get();
            }
        }
        assertThat(repository.findBatchesAwaitingForecast(routeVersionId, 10))
            .extracting(PendingForecastBatch::observationBatchId)
            .containsExactly(liveBatchId);

        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            "ALTER TABLE observation_batch DROP COLUMN ingestion_origin CASCADE")) {
            statement.execute();
        }
        assertThatThrownBy(() -> repository.findBatchesAwaitingForecast(routeVersionId, 10))
            .isInstanceOf(RuntimeException.class);
    }

    private static JdbcVehicleTrajectoryRepository repository() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return new JdbcVehicleTrajectoryRepository(JdbcClient.create(dataSource));
    }

    private static long insertRouteVersion(
        Connection connection
    ) throws Exception {
        long routeId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route (
                public_route_id, source_id, source_route_id, display_name,
                start_stop_name, end_stop_name)
            VALUES ('940000001', 'GBIS', '940000001', 'cache-race', 'start', 'end') RETURNING id
            """); ResultSet rows = statement.executeQuery()) {
            rows.next();
            routeId = rows.getLong(1);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route_version (route_id, content_digest, valid_from)
            VALUES (?, ?, now()) RETURNING id
            """)) {
            statement.setLong(1, routeId);
            statement.setString(2, "4".repeat(64));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long insertLiveBatch(
        Connection connection,
        long routeVersionId
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, outcome, provider_rows, stored_rows, excluded_rows,
                normalization_version, collection_strategy_version)
            VALUES (?, now(), 1, 'cache-live', now(), now(), 'SUCCESS_EMPTY',
                    0, 0, 0, 'normalization-v1.0.0', 'adaptive-kst-v1.0.1') RETURNING id
            """)) {
            statement.setLong(1, routeVersionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void insertBackfillBatch(
        Connection connection,
        long routeVersionId
    ) throws Exception {
        UUID importId = UUID.fromString("00000000-0000-4000-8000-000000000941");
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO historical_import_batch (
                id, manifest_sha256, archive_schema_version, archive_kind, inventory_sha256,
                source_cutoff_at, importer_version, target_kind, target_authority_from_min,
                route_validity_policy, status, expected_batch_count, expected_observation_count)
            VALUES (?, ?, 'fixture', 'BASE', ?, now(), 'fixture', 'LOCAL', now(),
                    'EXTEND_EXACT_CURRENT_VERSION', 'COMPLETE', 1, 0)
            """)) {
            statement.setObject(1, importId);
            statement.setString(2, "5".repeat(64));
            statement.setString(3, "6".repeat(64));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, outcome, provider_rows, stored_rows, excluded_rows,
                normalization_version, collection_strategy_version, ingestion_origin,
                historical_import_batch_id, semantic_batch_digest, normalized_record_sha256)
            VALUES (?, now(), 1, 'cache-backfill', now(), now(), 'SUCCESS_EMPTY',
                    0, 0, 0, 'normalization-v1.0.0', 'adaptive-kst-v1.0.1',
                    'S3_BACKFILL', ?, ?, ?)
            """)) {
            statement.setLong(1, routeVersionId);
            statement.setObject(2, importId);
            statement.setString(3, "7".repeat(64));
            statement.setString(4, "8".repeat(64));
            statement.executeUpdate();
        }
    }
}
