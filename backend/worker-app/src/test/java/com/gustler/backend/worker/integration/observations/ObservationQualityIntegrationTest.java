package com.gustler.backend.worker.integration.observations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.dto.BusLocationResponse;
import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import com.gustler.backend.observations.application.ObservationBatchLedger;
import com.gustler.backend.observations.application.ObservationLoader;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.quota.api.CallQuota;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
@Import({ PostgresTestContainer.class, CollectionRuntimeConfiguration.class,
        ObservationQualityIntegrationTest.QualityFailureConfiguration.class })
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

        batchId = ledger.reserve(new CollectionPlan(routeVersionId, SCHEDULED_AT, "quality-integration"), RESERVED_AT)
            .batchId();
        ledger.markDispatching(batchId, RESERVED_AT, REQUESTED_AT, "a");
    }

    @AfterEach
    void 저장한_자료를_정리한다() {
        failingQualityHook.fail = false;
        jdbc.sql("DELETE FROM vehicle_capacity WHERE vehicle_id IN (:vehicleIds)")
            .param("vehicleIds", List.of("verified-45", "verified-49", "ordinary-capacity-test")).update();
        jdbc.sql("""
            TRUNCATE vehicle_observation, observation_batch, route_stop, route_version, route,
                daily_call_quota RESTART IDENTITY CASCADE
            """).update();
    }

    @Test
    void 차종_기준을_넘으면_원값을_보존하고_편도_조사를_등록하지_않는다() {
        // given
        List<BusLocationResponse.BusLocation> buses = List.of(bus(VEHICLE_204000206, 1, STOP_205000217, 2, 71),
            bus(VEHICLE_204003542, 2, STOP_277103149, 0, 43));

        // when
        loadBuses(buses);

        // then
        assertThat(jdbc.sql(
            "SELECT count(*) FROM information_schema.tables WHERE table_name='trip_quality_rebuild' AND CAST(? AS bigint)>0")
            .param(routeVersionId).query(Integer.class).single()).isZero();
        assertThat(jdbc
            .sql(
                "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='stop_demand_rebuild_request' ")
            .query(Integer.class).single()).isZero();
        assertThat(readReportedSeats(VEHICLE_204000206)).containsExactly(71, null, "REVIEW");
        assertThat(jdbc.sql("""
            SELECT remaining_seats FROM operational_observation WHERE observation_batch_id = ?
            ORDER BY source_row_number
            """).param(batchId).query(Integer.class).list()).containsExactly(null, 43);
        assertThat(observationCount()).isEqualTo(2);
    }

    @Test
    void 차량별_기준을_차종보다_먼저_적용해_상향과_이상값을_구분한다() {
        // given
        String verified49 = "verified-49";
        String verified45 = "verified-45";
        jdbc.sql("""
            INSERT INTO vehicle_capacity (source_id, vehicle_id, capacity, evidence_at, verified_seat_limit)
            VALUES ('GBIS', ?, 44, ?, 49), ('GBIS', ?, 44, ?, 45)
            """).params(verified49, SCHEDULED_AT, verified45, SCHEDULED_AT).update();
        List<BusLocationResponse.BusLocation> buses = List.of(bus(verified49, 1, STOP_205000217, 2, 49),
            bus(verified45, 2, STOP_277103149, 0, 68));

        // when
        loadBuses(buses);

        // then
        assertThat(readReportedSeats(verified49)).containsExactly(null, 49, null);
        assertThat(readReportedSeats(verified45)).containsExactly(68, null, "OUTLIER");
        assertThat(jdbc.sql("""
            SELECT capacity FROM vehicle_capacity WHERE source_id = 'GBIS' AND vehicle_id IN (?, ?)
            ORDER BY capacity
            """).params(verified49, verified45).query(Integer.class).list()).containsExactly(44, 49);
    }

    @Test
    void 일반형이_연속으로_46석과_68석을_보고해도_정원은_45석에_머문다() {
        // given
        String vehicleId = "ordinary-capacity-test";
        loadBuses(List.of(bus(vehicleId, 1, STOP_205000217, 2, 45)));

        // when
        saveLaterSeats(vehicleId, 46, 1);
        saveLaterSeats(vehicleId, 68, 2);

        // then
        assertThat(jdbc.sql("""
            SELECT capacity FROM vehicle_capacity WHERE source_id = 'GBIS' AND vehicle_id = ?
            """).param(vehicleId).query(Integer.class).single()).isEqualTo(45);
        assertThat(jdbc.sql("""
            SELECT reported_remaining_seats FROM vehicle_observation
            WHERE route_version_id = ? AND vehicle_id = ? AND seat_quality_status = 'REVIEW'
            ORDER BY observation_batch_id
            """).params(routeVersionId, vehicleId).query(Integer.class).list()).containsExactly(46, 68);
        assertThat(jdbc
            .sql(
                """
                    SELECT count(*) FROM information_schema.tables WHERE table_name='trip_quality_rebuild' AND CAST(? AS bigint)>0
                    """)
            .param(routeVersionId).query(Integer.class).single()).isZero();
    }

    private void saveLaterSeats(String vehicleId, final int seats, final int offsetMinutes) {
        OffsetDateTime scheduledAt = SCHEDULED_AT.plusMinutes(offsetMinutes);
        OffsetDateTime requestedAt = REQUESTED_AT.plusMinutes(offsetMinutes);
        final long laterBatchId = ledger
            .reserve(new CollectionPlan(routeVersionId, scheduledAt, "seat-quality-" + offsetMinutes), requestedAt)
            .batchId();
        ledger.markDispatching(laterBatchId, requestedAt, requestedAt, "a");
        List<BusLocationResponse.BusLocation> buses = List.of(bus(vehicleId, 1, STOP_205000217, 2, seats));
        loader.load(laterBatchId, GbisObservationMapper.from(new GbisLocationResult.Success(QUERY_TIME, buses)),
            GbisObservationMapper.collect(buses), RESPONSE_RECEIVED_AT.plusMinutes(offsetMinutes));
    }

    private List<Object> readReportedSeats(String vehicleId) {
        return jdbc.sql("""
            SELECT reported_remaining_seats, remaining_seats, seat_quality_status
            FROM vehicle_observation WHERE observation_batch_id = ? AND vehicle_id = ?
            """).params(batchId, vehicleId).query((resultSet, rowNumber) -> Arrays.asList(resultSet.getObject(1),
            resultSet.getObject(2), resultSet.getObject(3))).single();
    }

    @Test
    void 정상_관측을_저장하면_편도와_조사_기록을_만들지_않는다() {
        // given
        List<BusLocationResponse.BusLocation> buses = List.of(bus(VEHICLE_204000206, 1, STOP_205000217, 2, 44));

        // when
        loadBuses(buses);

        // then
        assertThat(jdbc.sql(
            "SELECT count(*) FROM information_schema.tables WHERE table_name='trip_quality_rebuild' AND CAST(? AS bigint)>0")
            .param(routeVersionId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT vehicle_trip_key FROM vehicle_observation WHERE observation_batch_id=?")
            .param(batchId).query(String.class).list()).containsExactly((String) null);
    }

    @Test
    void 품질_처리가_실패하면_관측과_완료_상태는_롤백되고_전송_기록은_남는다() {
        // given
        failingQualityHook.fail = true;
        BusLocationResponse.BusLocation bus = bus(VEHICLE_204000206, 1, STOP_205000217, 2, 71);

        // when
        Throwable caughtFailure = catchThrowable(
            () -> ledger.conclude(
                batchId, receivedAt -> GbisObservationMapper
                    .response(new GbisLocationResult.Success(QUERY_TIME, List.of(bus)), receivedAt),
                RESPONSE_RECEIVED_AT));

        // then
        assertThat(caughtFailure).isNotNull().isInstanceOf(IllegalStateException.class).hasMessage("품질 처리 실패");
        assertThat(columnOf("outcome", String.class)).isEqualTo("DISPATCHING");
        assertThat(columnOf("requested_at", OffsetDateTime.class)).isEqualTo(REQUESTED_AT);
        assertThat(columnOf("response_received_at", OffsetDateTime.class)).isNull();
        assertThat(jdbc.sql("""
            SELECT reserved_calls FROM daily_call_quota
            WHERE provider = ? AND api_service = ? AND kst_date = ?
            """).params(CallQuota.BUS_LOCATION.provider(), CallQuota.BUS_LOCATION.apiService(), KOREA_8_19)
            .query(Integer.class).single()).isEqualTo(1);
        assertThat(observationCount()).isZero();
        assertThat(jdbc.sql(
            "SELECT count(*) FROM information_schema.tables WHERE table_name='trip_quality_rebuild' AND CAST(? AS bigint)>0")
            .param(routeVersionId).query(Integer.class).single()).isZero();
    }

    private void loadBuses(List<BusLocationResponse.BusLocation> buses) {
        loader.load(batchId, GbisObservationMapper.from(new GbisLocationResult.Success(QUERY_TIME, buses)),
            GbisObservationMapper.collect(buses), RESPONSE_RECEIVED_AT);
    }

    private static BusLocationResponse.BusLocation bus(
        String vehicleId,
        final int stopOrder,
        String stopId,
        final int runningState,
        final int remainingSeats
    ) {
        return new BusLocationResponse.BusLocation("경기70아0001", vehicleId, 0, ROUTE_3330, 11, stopId, stopOrder,
            runningState, remainingSeats, 3, 1);
    }

    private int observationCount() {
        return jdbc.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?").param(batchId)
            .query(Integer.class).single();
    }

    private <T> T columnOf(String column, Class<T> type) {
        return jdbc.sql("SELECT %s FROM observation_batch WHERE id = ?".formatted(column)).param(batchId).query(type)
            .optional().orElse(null);
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
        public Set<Long> observationsStored(VehicleObservationsStored observations) {
            if (fail) {
                throw new IllegalStateException("품질 처리 실패");
            }
            return Set.of();
        }
    }
}
