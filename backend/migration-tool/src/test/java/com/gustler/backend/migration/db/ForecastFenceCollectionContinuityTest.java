package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.processor.ForecastBatchWriter;
import com.gustler.backend.processor.ForecastJob;
import com.gustler.backend.processor.ForecastProperties;
import com.gustler.backend.processor.ForecastRuntime;
import com.gustler.backend.processor.PendingForecastBatch;
import com.gustler.backend.processor.RouteVersionRepository;
import com.gustler.backend.processor.SeatForecastRepository;
import com.gustler.backend.processor.StopDemandStatisticsRepository;
import com.gustler.backend.processor.VehicleTrajectoryRepository;
import com.gustler.backend.processor.persistence.jdbc.JdbcForecastWriteBarrier;
import com.gustler.backend.processor.seatdistribution.RuntimeSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ForecastFenceCollectionContinuityTest extends PostgresMigrationTestSupport {

    @BeforeAll
    static void migrateHistoricalSchema() {
        new HistoricalSchema().migrate(database);
    }

    @Test
    void exclusiveFenceSkipsForecastImmediatelyWhileCollectorInsertContinues() throws Exception {
        long routeVersionId;
        try (Connection connection = connection()) {
            routeVersionId = insertRoute(connection, "1");
        }
        try (Connection exclusive = connection()) {
            exclusive.setAutoCommit(false);
            try (PreparedStatement statement = exclusive.prepareStatement(
                "SELECT pg_advisory_xact_lock(?, ?)")) {
                statement.setInt(1, JdbcForecastWriteBarrier.LOCK_NAMESPACE);
                statement.setInt(2, JdbcForecastWriteBarrier.LOCK_KEY);
                statement.executeQuery().close();
            }

            Instant barrierStarted = Instant.now();
            assertThat(enterBarrier()).isFalse();
            assertThat(Duration.between(barrierStarted, Instant.now())).isLessThan(Duration.ofSeconds(1));

            Instant insertStarted = Instant.now();
            long batchId = insertCollectionBatch(routeVersionId);
            assertThat(Duration.between(insertStarted, Instant.now())).isLessThan(Duration.ofSeconds(1));
            assertThat(batchId).isPositive();
            exclusive.rollback();
        }
    }

    @Test
    void persistedPauseFlagSkipsTheRealBarrierWithoutAnExclusiveLock() throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            UPDATE forecast_cutover_control
            SET writes_paused=true,
                cutover_id='00000000-0000-4000-8000-000000000901',
                pause_reason='TEST', paused_at=now(), observation_batch_high_water=0
            WHERE singleton=true
            """)) {
            statement.executeUpdate();
        }
        try {
            assertThat(enterBarrier()).isFalse();
        } finally {
            try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE forecast_cutover_control
                SET writes_paused=false, cutover_id=NULL, pause_reason=NULL, paused_at=NULL,
                    observation_batch_high_water=NULL
                WHERE singleton=true
                """)) {
                statement.executeUpdate();
            }
        }
    }

    @Test
    void actualForecastJobSkipsWhileCollectorInsertCompletesUnderExclusiveFence() throws Exception {
        long routeVersionId;
        try (Connection connection = connection()) {
            routeVersionId = insertRoute(connection, "2");
        }
        VehicleTrajectoryRepository trajectories = mock(VehicleTrajectoryRepository.class);
        RouteVersionRepository routes = mock(RouteVersionRepository.class);
        ForecastRuntime runtime = mock(ForecastRuntime.class);
        RuntimeSnapshot snapshot = mock(RuntimeSnapshot.class);
        SeatForecastRepository forecasts = mock(SeatForecastRepository.class);
        StopDemandStatisticsRepository statistics = mock(StopDemandStatisticsRepository.class);
        when(routes.findActiveVersionIds()).thenReturn(List.of(routeVersionId));
        when(trajectories.findBatchesAwaitingForecast(routeVersionId, 1))
            .thenReturn(List.of(new PendingForecastBatch(1, routeVersionId, Instant.now())));
        when(runtime.resolveActive()).thenReturn(Optional.of(snapshot));
        JdbcForecastWriteBarrier barrier = new JdbcForecastWriteBarrier(JdbcClient.create(dataSource()));
        ForecastBatchWriter writer = new ForecastBatchWriter(
            trajectories, forecasts, statistics, barrier, java.time.Clock.systemUTC());
        ForecastJob job = new ForecastJob(
            trajectories, routes, runtime, writer,
            new ForecastProperties(
                true, Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofHours(6), 1, 1, 1));

        try (Connection exclusive = connection(); ExecutorService executor = Executors.newFixedThreadPool(2)) {
            exclusive.setAutoCommit(false);
            try (PreparedStatement statement = exclusive.prepareStatement(
                "SELECT pg_advisory_xact_lock(?, ?)")) {
                statement.setInt(1, JdbcForecastWriteBarrier.LOCK_NAMESPACE);
                statement.setInt(2, JdbcForecastWriteBarrier.LOCK_KEY);
                statement.executeQuery().close();
            }
            Future<?> forecastCycle = executor.submit(() ->
                new TransactionTemplate(new DataSourceTransactionManager(dataSource()))
                    .executeWithoutResult(status -> job.writeForecasts()));
            Future<Long> collection = executor.submit(() -> insertCollectionBatch(routeVersionId));

            forecastCycle.get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(collection.get(1, java.util.concurrent.TimeUnit.SECONDS)).isPositive();
            verifyNoInteractions(forecasts, statistics);
            verify(trajectories, never()).readTrajectories(1);
            exclusive.rollback();
        }
    }

    private static boolean enterBarrier() {
        PGSimpleDataSource dataSource = dataSource();
        JdbcForecastWriteBarrier barrier = new JdbcForecastWriteBarrier(JdbcClient.create(dataSource));
        Boolean entered = new TransactionTemplate(new DataSourceTransactionManager(dataSource))
            .execute(status -> barrier.enter());
        return Boolean.TRUE.equals(entered);
    }

    private static long insertCollectionBatch(
        long routeVersionId
    ) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, outcome, provider_rows, stored_rows, excluded_rows,
                normalization_version, collection_strategy_version)
            VALUES (?, now(), 1, 'collector-continues-under-forecast-fence', now(), now(),
                    'SUCCESS_EMPTY', 0, 0, 0, 'normalization-v1.0.0', 'adaptive-kst-v1.0.1')
            RETURNING id
            """)) {
            statement.setLong(1, routeVersionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long insertRoute(
        Connection connection,
        String suffix
    ) throws Exception {
        long routeId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route (
                public_route_id, source_id, source_route_id, display_name,
                start_stop_name, end_stop_name)
            VALUES (?, 'GBIS', ?, ?, 'start', 'end')
            RETURNING id
            """)) {
            statement.setString(1, "90000002" + suffix);
            statement.setString(2, "90000002" + suffix);
            statement.setString(3, "fence-test-" + suffix);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                routeId = rows.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route_version (route_id, content_digest, valid_from)
            VALUES (?, ?, '2000-01-01T00:00:00Z') RETURNING id
            """)) {
            statement.setLong(1, routeId);
            statement.setString(2, "9".repeat(64));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }
}
