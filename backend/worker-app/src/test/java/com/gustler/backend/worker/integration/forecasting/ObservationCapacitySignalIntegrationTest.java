package com.gustler.backend.worker.integration.forecasting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.gustler.backend.forecasting.api.forecast.ForecastLanes;
import com.gustler.backend.forecasting.api.forecast.RunForecasts;
import com.gustler.backend.forecasting.api.forecast.ScoringCycle;
import com.gustler.backend.forecasting.api.forecast.ScoringFeed;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.dto.BusLocationResponse;
import com.gustler.backend.observations.application.CollectionBatchService;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수집이 커밋한 정원이 예보 신호보다 먼저 worker 메모리에 반영되는지 본다. 활성 모델이 없어 예보는 배치를 큐에 넣지 않는다.
 *
 * <p>worker가 읽지 않는 예보 설정 키를 같이 준다. 서버의 환경 파일에 그런 키가 남아 있어도 기동과 구성이 달라지지 않아야 한다.
 */
@SpringBootTest(classes = WorkerApplication.class, properties = {
    "forecast.enabled=true",
    "forecast.interval=1h",
    "forecast.compact-settlement-write=true",
    "forecast.settlement-interval=0s",
    "forecast.settlement-minimum-interval=1h",
    "forecast.pending-limit=0",
    "forecast.arrival-limit=0",
    "forecast.alighting.history-seed-directory=/nonexistent",
    "forecast.alighting.rush-only=false",
    "forecast.alighting.maximum-history-rows=0",
    "forecast.alighting.causal-evaluation.maximum-observation-rows=0"
})
@Import({ PostgresTestContainer.class, CollectionRuntimeConfiguration.class })
class ObservationCapacitySignalIntegrationTest {

    private static final String SOURCE_ROUTE = "204000057";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration SIGNAL_DEADLINE = Duration.ofSeconds(5);
    private static final List<String> VEHICLES = List.of("capacity-commit-test", "capacity-rollback-test");

    @Autowired
    private CollectionBatchService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private VehicleCapacityCache vehicleCapacities;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private ForecastRuntime forecastRuntime;

    @MockitoSpyBean
    private RunForecasts forecasts;

    private long routeVersionId;

    @BeforeEach
    void 수집할_노선을_준비한다() {
        OffsetDateTime validFrom = OffsetDateTime.now(SEOUL).minusDays(1);
        final long routeId = jdbc.sql("""
            INSERT INTO route (public_route_id, source_id, source_route_id, display_name, start_stop_name,
                end_stop_name)
            VALUES (:source, 'GBIS', :source, '3330', '출발', '도착') RETURNING id
            """).param("source", SOURCE_ROUTE).query(Long.class).single();
        routeVersionId = jdbc.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
            VALUES (:route, :digest, :at, 3) RETURNING id
            """).param("route", routeId).param("digest", "0".repeat(64)).param("at", validFrom).query(Long.class)
            .single();
        jdbc.sql("""
            INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (:version, 1, 'stop-one', '출발', 'UP', true),
                   (:version, 2, 'stop-two', '중간', 'UP', true),
                   (:version, 3, 'stop-three', '도착', 'UP', true)
            """).param("version", routeVersionId).update();
    }

    @AfterEach
    void 저장한_자료를_정리한다() {
        jdbc.sql("DELETE FROM vehicle_capacity WHERE vehicle_id IN (:vehicleIds)").param("vehicleIds", VEHICLES)
            .update();
        jdbc.sql("TRUNCATE route, daily_call_quota CASCADE").update();
    }

    @Test
    void 읽지_않는_예보_설정_키가_남아_있어도_예보와_채점_구성이_선다() {
        // then
        assertSoftly(softly -> {
            softly.assertThat(context.getBeanNamesForType(RunForecasts.class)).hasSize(1);
            softly.assertThat(context.getBeanNamesForType(ForecastLanes.class)).hasSize(1);
            softly.assertThat(context.getBeanNamesForType(ScoringCycle.class)).hasSize(1);
            softly.assertThat(context.getBeanNamesForType(ScoringFeed.class)).hasSize(1);
        });
    }

    @Test
    void 수집이_커밋되면_예보_신호_전에_캐시를_갱신한다() {
        // given
        String vehicleId = "capacity-commit-test";
        jdbc.sql("""
            INSERT INTO vehicle_capacity (source_id, vehicle_id, capacity, evidence_at)
            VALUES ('GBIS', ?, 44, now())
            """).param(vehicleId).update();
        assertThat(vehicleCapacities.find("GBIS", List.of(vehicleId)).get(vehicleId).seats()).isEqualTo(44);
        AtomicInteger seenAtSignal = new AtomicInteger();
        doAnswer(invocation -> {
            seenAtSignal.set(vehicleCapacities.find("GBIS", List.of(vehicleId)).get(vehicleId).seats());
            return invocation.callRealMethod();
        }).when(forecasts).batchStored(anyLong(), eq(routeVersionId));

        // when
        final long batchId = collectWithSeats(vehicleId, 45);

        // then
        verify(forecasts, timeout(SIGNAL_DEADLINE.toMillis())).batchStored(batchId, routeVersionId);
        assertSoftly(checks -> {
            checks.assertThat(seenAtSignal.get()).isEqualTo(45);
            checks.assertThat(readCapacity(vehicleId)).contains(45);
        });
        final long nextBatchId = collectWithSeats(vehicleId, 44);
        assertThat(readCapacity(vehicleId)).contains(45);
        verify(forecasts, timeout(SIGNAL_DEADLINE.toMillis())).batchStored(nextBatchId, routeVersionId);
    }

    @Test
    void 관측_저장이_롤백되면_정원과_캐시도_바뀌지_않는다() {
        // given
        String vehicleId = "capacity-rollback-test";

        // when
        transactions.executeWithoutResult(status -> {
            collectWithSeats(vehicleId, 45);
            assertThat(readCapacity(vehicleId)).contains(45);
            status.setRollbackOnly();
        });

        // then
        assertSoftly(checks -> {
            checks.assertThat(readCapacity(vehicleId)).isEmpty();
            checks.assertThat(vehicleCapacities.find("GBIS", List.of(vehicleId))).isEmpty();
        });
    }

    private long collectWithSeats(String vehicleId, final int seats) {
        OffsetDateTime at = OffsetDateTime.now(SEOUL).truncatedTo(ChronoUnit.MILLIS);
        final long batchId = ledger.prepare(new CollectionPlan(routeVersionId, at, UUID.randomUUID().toString()), at)
            .batchId();

        BusLocationResponse.BusLocation bus = new BusLocationResponse.BusLocation(
                "경기70아0001",
                vehicleId,
                0,
                SOURCE_ROUTE,
                11,
                "stop-one",
                1,
                2,
                seats,
                3,
                1
        );
        ledger.conclude(batchId,
            received -> GbisObservationMapper.response(new GbisLocationResult.Success(
                at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S")), List.of(bus)), received),
            at.plusNanos(1_000_000));
        return batchId;
    }

    private Optional<Integer> readCapacity(String vehicleId) {
        return jdbc.sql("SELECT capacity FROM vehicle_capacity WHERE source_id = 'GBIS' AND vehicle_id = ?")
            .param(vehicleId).query(Integer.class).optional();
    }
}
