package com.gustler.backend.worker.integration.observations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.gbis.api.GbisLocationResult.Success;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.ObservedSeatValue;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import com.gustler.backend.observations.application.ObservationBatchLedger;
import com.gustler.backend.observations.application.ObservationLoader;
import com.gustler.backend.observations.domain.CollectionAttemptToken;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.quota.domain.CallQuota;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = WorkerApplication.class)
@Import({PostgresTestContainer.class, CollectionRuntimeConfiguration.class,
    ObservationQualityIntegrationTest.QualityFailureConfiguration.class})
class ObservationQualityIntegrationTest {

    private static final String ROUTE_3330 = "204000057";
    private static final String STOP_205000217 = "205000217";
    private static final String STOP_277103149 = "277103149";
    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";
    private static final String QUERY_TIME = "2026-08-19 11:14:04.9";
    private static final OffsetDateTime SCHEDULED_AT = OffsetDateTime.parse("2026-08-19T11:14:00+09:00");
    private static final OffsetDateTime RESERVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.700+09:00");
    private static final OffsetDateTime REQUESTED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.800+09:00");
    private static final OffsetDateTime RESPONSE_RECEIVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final LocalDate KOREA_8_19 = LocalDate.of(2026, 8, 19);

    @Autowired
    private ObservationBatchLedger ledger;

    @Autowired
    private ObservationLoader loader;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private FailingQualityHook failingQualityHook;

    private long routeVersionId;
    private long batchId;
    private CollectionAttemptToken token;

    @BeforeEach
    void 노선과_정류소를_저장하고_수집_전송을_기록한다() {
        final long routeId = jdbc.sql("""
                INSERT INTO route (public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name)
                VALUES (?, 'GBIS', ?, '3330', '범계역', '강남역') RETURNING id
                """).params(ROUTE_3330, ROUTE_3330).query(Long.class).single();
        routeVersionId = jdbc.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
                VALUES (?, ?, ?, 2) RETURNING id
                """).params(routeId, "0".repeat(64), SCHEDULED_AT).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
                VALUES (?, 1, ?, '범계역', 'UP', true), (?, 2, ?, '안양대교(경유)', 'UP', false),
                       (?, 3, '208000069', '안양역', 'DOWN', true)
                """).params(routeVersionId, STOP_205000217, routeVersionId, STOP_277103149, routeVersionId).update();

        token = ledger.reserve(new CollectionPlan(routeVersionId, SCHEDULED_AT, "quality-integration"), RESERVED_AT).token();
        batchId = token.batchId();
        ledger.markDispatching(token, RESERVED_AT, REQUESTED_AT);
    }

    @AfterEach
    void 저장한_자료를_정리한다() {
        failingQualityHook.fail = false;
        jdbc.sql("""
                TRUNCATE vehicle_observation, observation_batch, route_stop, route_version, route,
                    daily_call_quota RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void 잔여석_71을_저장하면_같은_트랜잭션에서_조사를_등록하고_정상_차량은_유지한다() {
        // given
        List<BusLocation> buses = List.of(
            bus(VEHICLE_204000206, 1, STOP_205000217, 2, 71),
            bus(VEHICLE_204003542, 2, STOP_277103149, 0, 43));

        // when
        loadBuses(buses);

        // then
        assertThat(jdbc.sql("SELECT vehicle_id FROM trip_quality_rebuild WHERE route_version_id=?")
            .param(routeVersionId).query(String.class).list()).containsExactly(VEHICLE_204000206);
        assertThat(jdbc.sql("SELECT vehicle_id FROM forecast_eligible_observation WHERE route_version_id=?")
            .param(routeVersionId).query(String.class).list()).containsExactly(VEHICLE_204003542);
        assertThat(observationCount()).isEqualTo(2);
    }

    @Test
    void 정상_관측을_저장하면_편도와_조사_기록을_만들지_않는다() {
        // given
        List<BusLocation> buses = List.of(bus(VEHICLE_204000206, 1, STOP_205000217, 2, 44));

        // when
        loadBuses(buses);

        // then
        assertThat(jdbc.sql("SELECT count(*) FROM trip_quality_rebuild WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT vehicle_trip_key FROM vehicle_observation WHERE observation_batch_id=?")
            .param(batchId).query(String.class).list()).containsExactly((String) null);
    }

    @Test
    void 품질_처리가_실패하면_관측과_완료_상태는_롤백되고_전송_기록은_남는다() {
        // given
        failingQualityHook.fail = true;
        BusLocation bus = bus(VEHICLE_204000206, 1, STOP_205000217, 2, 71);

        // when & then
        assertThatThrownBy(() -> ledger.conclude(token,
            GbisObservationMapper.response(new Success(QUERY_TIME, List.of(bus)), RESPONSE_RECEIVED_AT)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("품질 처리 실패");
        assertThat(columnOf("outcome", String.class)).isEqualTo("DISPATCHING");
        assertThat(columnOf("requested_at", OffsetDateTime.class)).isEqualTo(REQUESTED_AT);
        assertThat(columnOf("response_received_at", OffsetDateTime.class)).isNull();
        assertThat(jdbc.sql("""
                SELECT reserved_calls FROM daily_call_quota
                WHERE provider = ? AND api_service = ? AND kst_date = ?
                """).params(CallQuota.BUS_LOCATION.provider(), CallQuota.BUS_LOCATION.apiService(), KOREA_8_19)
            .query(Integer.class).single()).isEqualTo(1);
        assertThat(observationCount()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM trip_quality_rebuild WHERE route_version_id = ?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
    }

    private void loadBuses(List<BusLocation> buses) {
        loader.load(token, GbisObservationMapper.from(new Success(QUERY_TIME, buses)),
            GbisObservationMapper.collect(buses), RESPONSE_RECEIVED_AT);
    }

    private static BusLocation bus(String vehicleId, final int stopOrder, String stopId,
                                   final int runningState, final int remainingSeats) {
        return new BusLocation("경기70아0001", vehicleId, 0, ROUTE_3330, 11,
            stopId, stopOrder, runningState, remainingSeats, 3, 1);
    }

    private int observationCount() {
        return jdbc.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?")
            .param(batchId).query(Integer.class).single();
    }

    private <T> T columnOf(String column, Class<T> type) {
        return jdbc.sql("SELECT %s FROM observation_batch WHERE id = ?".formatted(column))
            .param(batchId).query(type).optional().orElse(null);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QualityFailureConfiguration {

        @Bean
        FailingQualityHook failingQualityHook() {
            return new FailingQualityHook();
        }
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    static class FailingQualityHook implements CollectionQualityHook {

        private boolean fail;

        @Override
        public void beforeRowsStored(final long routeVersionId, List<ObservedSeatValue> rows) {
        }

        @Override
        public void observationsStored(VehicleObservationsStored observations) {
            if (fail) {
                throw new IllegalStateException("품질 처리 실패");
            }
        }
    }
}
