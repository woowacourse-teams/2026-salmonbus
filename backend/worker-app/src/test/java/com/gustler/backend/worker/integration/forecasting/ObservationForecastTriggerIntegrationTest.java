package com.gustler.backend.worker.integration.forecasting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.api.publication.PublishPendingForecasts;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.ModelRoute;
import com.gustler.backend.forecasting.domain.model.SeatDistribution;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.gbis.api.GbisLocationResult.Success;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.observations.application.ObservationBatchLedger;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(classes = WorkerApplication.class, properties = {"forecast.enabled=true", "forecast.interval=1h"})
@Import({PostgresTestContainer.class, CollectionRuntimeConfiguration.class})
class ObservationForecastTriggerIntegrationTest {
    private static final String SOURCE_ROUTE = "204000057";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration SIGNAL_DEADLINE = Duration.ofSeconds(5);

    @Autowired
    private ObservationBatchLedger ledger;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private ForecastRuntime forecastRuntime;

    @MockitoSpyBean
    private PublishPendingForecasts publications;

    private long routeVersionId;

    @BeforeEach
    void 예보할_노선과_도는_모델을_준비한다() {
        OffsetDateTime validFrom = OffsetDateTime.now(SEOUL).minusDays(1);
        final long routeId = jdbc.sql("""
            INSERT INTO route (public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name)
            VALUES (:source, 'GBIS', :source, '3330', '출발', '도착') RETURNING id
            """)
            .param("source", SOURCE_ROUTE)
            .query(Long.class)
            .single();
        routeVersionId = jdbc.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
            VALUES (:route, :digest, :at, 3) RETURNING id
            """).param("route", routeId).param("digest", "0".repeat(64)).param("at", validFrom)
            .query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (:version, 1, 'stop-one', '출발', 'UP', true),
                   (:version, 2, 'stop-two', '중간', 'UP', true),
                   (:version, 3, 'stop-three', '도착', 'UP', true)
            """).param("version", routeVersionId).update();
        final long deploymentId = jdbc.sql("""
            INSERT INTO model_deployment (deployment_key, release_id, model_key, model_version, bundle_digest,
                prediction_target_version, calculation_version, supported_scope_digest, data_until, state)
            VALUES (?, 'trigger-test', 'seat-full-chance', '1', ?, 'SEAT_FULL_CHANCE_V1', 'CALCULATION_V1', ?, ?, 'ACTIVE')
            RETURNING id
            """)
            .params(UUID.randomUUID(), "0".repeat(64), "0".repeat(64), validFrom)
            .query(Long.class)
            .single();
        RuntimeSnapshot runtime = new RuntimeSnapshot(
            new ActiveModelDeployment(deploymentId, "CALCULATION_V1", "trigger-test", "0".repeat(64)),
            new SupportedForecastScope(List.of(ModelRoute.of(SOURCE_ROUTE))),
            input -> new SeatForecastResult(new SeatDistribution(List.of(0.4, 0.6)), 0.4),
            validFrom.toInstant());
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
    }

    @AfterEach
    void 저장한_자료를_정리한다() {
        jdbc.sql("TRUNCATE route, model_deployment, daily_call_quota RESTART IDENTITY CASCADE").update();
    }

    @Test
    void 관측_저장이_커밋되면_회차를_기다리지_않고_그_batch의_예보를_발행한다() throws Exception {
        // given
        OffsetDateTime at = OffsetDateTime.now(SEOUL).truncatedTo(ChronoUnit.MILLIS);
        final long batchId = ledger.reserve(new CollectionPlan(routeVersionId, at, "trigger-test"), at).batchId();
        ledger.markDispatching(batchId, at, at, "a");
        BusLocation bus = new BusLocation("경기70아0001", "trigger-test-vehicle", 0, SOURCE_ROUTE, 11,
            "stop-one", 1, 2, 20, 3, 1);

        // when
        ledger.conclude(batchId, received -> GbisObservationMapper.response(
            new Success(at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S")), List.of(bus)), received),
            at.plusNanos(1_000_000));
        Instant committedAt = Instant.now();

        // then
        Instant publishedAt = waitForPublication(batchId, committedAt.plus(SIGNAL_DEADLINE));
        assertThat(publishedAt).isNotNull();
        assertThat(Duration.between(committedAt, publishedAt)).isLessThan(SIGNAL_DEADLINE);
        verify(publications, timeout(SIGNAL_DEADLINE.toMillis())).writeForecastsOfRoute(routeVersionId);
    }

    private Instant waitForPublication(final long batchId, Instant deadline) throws InterruptedException {
        while (Instant.now().isBefore(deadline)) {
            Optional<Long> published = jdbc.sql("SELECT id FROM forecast_publication WHERE source_batch_id = ?")
                .param(batchId).query(Long.class).optional();
            if (published.isPresent()) {
                return Instant.now();
            }
            Thread.sleep(20);
        }
        return null;
    }
}
