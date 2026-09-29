package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.evaluation.ForecastEvaluationWriter;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.quality.RouteDataQuality;
import com.gustler.backend.forecasting.domain.quality.RouteDataQualityRepository;
import com.gustler.backend.forecasting.application.statistics.DemandAccumulator;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsPipeline;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsRebuildRequestStore;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsRebuildStore;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsRebuilder;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsStore;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.domain.statistics.DemandSampleStore;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuildRepository;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import com.gustler.backend.forecasting.domain.statistics.StopDemandAggregator;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@ForecastingIntegrationTest
@Transactional
class DemandStatisticsPipelineTest {

    /** 판마다 남는 두 판본. 기본값이 없어서 픽스처가 직접 넣는다. */
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String COLLECTION_STRATEGY_VERSION = "adaptive-kst-v1.0.1";

    /** 지나감(2)으로 넣어서 통과 순번이 상류 순번과 같다. 관측 시각 열은 SAL-84 가 지웠다. */
    private static final int RUNNING_STATE_DEPARTED = 2;
    private static final int SEATS_LEFT = 12;

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String VEHICLE_204000206 = "204000206";
    private static final int PASSED_STOP_ORDER = 8;
    private static final int TARGET_STOP_ORDER = 9;
    private static final int NEXT_TARGET_STOP_ORDER = 10;
    private static final int ARRIVAL_STOP_ORDER = 9;
    private static final int DEMAND_STATISTICS_REVISION = 3;

    /** 판이 상류 응답을 받은 시각. 관측 시각의 권위가 여기 있다. */
    private static final OffsetDateTime RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final OffsetDateTime ARRIVAL_RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:20:31.402+09:00");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-19T02:14:05Z");
    private static final Instant SCORED_AT = Instant.parse("2026-08-19T02:25:00Z");
    private static final Instant PIPELINE_NOW = Instant.parse("2026-09-25T00:00:00Z");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ForecastEvaluationWriter evaluationWriter;

    @Autowired
    private DemandAccumulator accumulator;

    @Autowired
    private DemandStatisticsRebuilder rebuilder;

    @Autowired
    private DemandStatisticsRebuildRequestStore rebuildRequests;

    @Autowired
    private DemandStatisticsRebuildRepository rebuilds;

    @Autowired
    private DemandStatisticsRebuildStore rebuildStore;

    @Autowired
    private DemandStatisticsStore statisticsStore;

    @Autowired
    private DemandSampleStore samples;

    @Autowired
    private RouteDataQualityAccess qualityAccess;

    @Autowired
    private RouteDataQualityRepository qualities;

    @Autowired
    private DemandStatisticsPipeline pipeline;

    @Autowired
    private JdbcStopDemandStatisticsRepository statistics;

    @MockitoBean
    private Clock clock;

    private long routeId;
    private long routeVersionId;
    private long modelDeploymentId;
    private long observationBatchId;
    private long vehicleObservationId;

    @BeforeEach
    void 노선_판본과_정류소와_모델과_관측을_먼저_저장한다() {
        when(clock.instant()).thenReturn(PIPELINE_NOW);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        routeId = insertRoute();
        routeVersionId = insertRouteVersion(routeId);
        qualityAccess.lockByRoute(routeId);
        insertRouteStop(PASSED_STOP_ORDER);
        insertRouteStop(TARGET_STOP_ORDER);
        insertRouteStop(NEXT_TARGET_STOP_ORDER);
        modelDeploymentId = insertModelDeployment();
        observationBatchId = insertObservationBatch("2026-08-19T11:14", RESPONSE_RECEIVED_AT);
        vehicleObservationId = insertObservation(observationBatchId, VEHICLE_204000206, 0, PASSED_STOP_ORDER);
    }

    @Test
    void 누적은_한_페이지씩_진행하고_재시도해도_합계가_늘지_않는다() {
        givenStatisticsSample();
        // 한 SQL 페이지보다 큰 합성 입력. 처리량 제한과 이어하기를 확인한다.
        jdbcClient.sql("""
            INSERT INTO stop_demand_pending_sample(route_version_id, prediction_observation_id,
                arrival_observation_id, vehicle_id, target_stop_order, arrived_at, scored_at,
                prediction_remaining_seats, arrival_remaining_seats)
            SELECT route_version_id, prediction_observation_id, arrival_observation_id, vehicle_id,
                target_stop_order, arrived_at, scored_at, prediction_remaining_seats, arrival_remaining_seats
            FROM stop_demand_pending_sample CROSS JOIN generate_series(1, 128)
            """).update();

        var first = accumulator.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);
        assertThat(first.applied()).isEqualTo(128);
        assertThat(pendingSampleCount()).isEqualTo(1);
        var second = accumulator.apply(routeVersionId, VEHICLE_204000206, first.nextInputId(), Long.MAX_VALUE, SCORED_AT);
        assertThat(second.applied()).isEqualTo(1);
        accumulator.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);

        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(129);
        assertThat(jdbcClient.sql("SELECT net_boarding_sum FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(129L * SEATS_LEFT);
    }

    @Test
    void 하차로_잔여석이_늘어도_원합은_기존_통계와_같은_값을_복원한다() {
        long arrival = insertArrivalObservation();
        jdbcClient.sql("UPDATE vehicle_observation SET remaining_seats=20 WHERE id=?").param(arrival).update();
        saveForecasts(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));
        evaluationWriter.complete(List.of(ForecastEvaluation.completed(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 20), SCORED_AT)));
        var original = statistics.readHourlyTotals(routeVersionId, SCORED_AT).getFirst();

        accumulator.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);

        var sums = jdbcClient.sql("SELECT sample_count, arrival_seats_sum, net_boarding_sum FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();
        assertThat(sums).containsExactly(1, 20, -8);
        assertThat(sums[0] - sums[1] / 20.0).isEqualTo(original.fillRateTotal());
        assertThat((double) sums[2]).isEqualTo(original.netBoardingTotal());
        assertThat(sums[0] * 20.0).isEqualTo(original.capacityTotal());
    }

    @Test
    void 누적_롤백은_합계와_대기_자료를_함께_되돌린다() {
        givenStatisticsSample();
        jdbcClient.sql("SAVEPOINT before_accumulation").update();
        accumulator.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);
        assertThat(pendingSampleCount()).isZero();

        jdbcClient.sql("ROLLBACK TO SAVEPOINT before_accumulation").update();

        assertThat(pendingSampleCount()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
    }

    @Test
    void 누적_직전_품질이_바뀌면_자료를_남기고_정정을_요청한다() {
        givenStatisticsSample();
        jdbcClient.sql("UPDATE vehicle_one_way_trip SET status='EXCLUDED' WHERE start_observation_id=?")
            .param(vehicleObservationId).update();

        var result = accumulator.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);

        assertThat(result.waitingForRebuild()).isTrue();
        assertThat(result.applied()).isZero();
        assertThat(pendingSampleCount()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_rebuild_request WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isEqualTo(1);
        assertThat(accumulator.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT).selected()).isZero();
    }

    @Test
    void 빈_차량_ID의_대기_자료가_있어도_누적을_마친다() {
        givenStatisticsSample();
        finishPipeline();
        when(clock.instant()).thenReturn(PIPELINE_NOW.plusSeconds(21600));
        addLateSample("blank-vehicle", 1);
        jdbcClient.sql("UPDATE stop_demand_pending_sample SET vehicle_id='' WHERE route_version_id=?")
            .param(routeVersionId).update();

        finishPipeline();

        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
    }

    @Test
    void 빈_차량_ID의_대기_자료가_정정을_요구하면_노선_전체_정정을_요청한다() {
        givenStatisticsSample();
        jdbcClient.sql("UPDATE stop_demand_pending_sample SET vehicle_id='' WHERE route_version_id=?")
            .param(routeVersionId).update();
        jdbcClient.sql("UPDATE vehicle_one_way_trip SET status='EXCLUDED' WHERE start_observation_id=?")
            .param(vehicleObservationId).update();

        var result = accumulator.apply(routeVersionId, "", 0, Long.MAX_VALUE, SCORED_AT);

        assertThat(result.waitingForRebuild()).isTrue();
        assertThat(rebuildRequest("")).isNotNull();
    }

    @Test
    void 고정한_입력_범위나_기준시각_밖의_자료는_다음_처리를_위해_남긴다() {
        givenStatisticsSample();
        long id = jdbcClient.sql("SELECT id FROM stop_demand_pending_sample WHERE prediction_observation_id=?")
            .param(vehicleObservationId).query(Long.class).single();

        assertThat(accumulator.apply(routeVersionId, VEHICLE_204000206, 0, id - 1, SCORED_AT).applied()).isZero();
        assertThat(accumulator.apply(routeVersionId, VEHICLE_204000206, 0, id, SCORED_AT.minusSeconds(1)).applied()).isZero();
        assertThat(pendingSampleCount()).isEqualTo(1);
        assertThat(accumulator.apply(routeVersionId, VEHICLE_204000206, 0, id, SCORED_AT).applied()).isEqualTo(1);
    }

    @Test
    void 정정은_이상_편도의_기여분을_제거하고_빈_완료도_표현한다() {
        givenStatisticsSample();
        accumulator.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);
        jdbcClient.sql("UPDATE vehicle_one_way_trip SET status='EXCLUDED' WHERE start_observation_id=?")
            .param(vehicleObservationId).update();
        rebuildRequests.request(routeVersionId, RebuildScope.wholeRoute());

        finishRebuild("");

        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT initialized FROM stop_demand_baseline WHERE route_version_id=?")
            .param(routeVersionId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void 정정_시작_뒤의_정산은_이번_교체에서_제외하고_다음_누적에_한번만_쓴다() {
        givenStatisticsSample();
        long batch = insertObservationBatch("late-source", RESPONSE_RECEIVED_AT.plusSeconds(1));
        long source = insertObservation(batch, VEHICLE_204000206, 0, PASSED_STOP_ORDER);
        saveForecasts(List.of(new SeatForecast(source, routeVersionId, TARGET_STOP_ORDER, 1,
            modelDeploymentId, DEMAND_STATISTICS_REVISION, 0.41, 0.38, 12.5, GENERATED_AT)));
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));
        rebuilder.step(routeVersionId, VEHICLE_204000206); // 범위 고정
        long arrival = jdbcClient.sql("SELECT arrival_observation_id FROM stop_demand_pending_sample WHERE prediction_observation_id=?")
            .param(vehicleObservationId).query(Long.class).single();
        evaluationWriter.complete(List.of(ForecastEvaluation.completed(
            source, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));

        finishRebuild(VEHICLE_204000206);

        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isEqualTo(1);
        accumulator.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);
        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void 정정_페이지의_중간_실패와_재요청에도_합계가_중복되지_않는다() {
        givenStatisticsSample();
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));
        rebuilder.step(routeVersionId, VEHICLE_204000206);
        jdbcClient.sql("SAVEPOINT before_rebuild_page").update();
        rebuilder.step(routeVersionId, VEHICLE_204000206);
        jdbcClient.sql("ROLLBACK TO SAVEPOINT before_rebuild_page").update();
        rebuilder.step(routeVersionId, VEHICLE_204000206);
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));

        finishRebuild(VEHICLE_204000206);

        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(1);
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 다른_노선의_관측을_정정_페이지에_넣지_않는다() {
        givenStatisticsSample();
        long selectedVersion = routeVersionId;
        long otherRoute = jdbcClient.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES ('other-scan','GBIS','other-scan','other','start','end') RETURNING id
            """).query(Long.class).single();
        routeVersionId = insertRouteVersion(otherRoute);
        insertRouteStop(PASSED_STOP_ORDER);
        long noise = insertObservationBatch("noise", RESPONSE_RECEIVED_AT.minusSeconds(1));
        for (int i = 0; i < 150; i++) {
            insertObservation(noise, "noise-" + i, i, PASSED_STOP_ORDER);
        }
        routeVersionId = selectedVersion;
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));
        rebuilder.step(routeVersionId, VEHICLE_204000206);
        rebuilder.step(routeVersionId, VEHICLE_204000206);

        assertThat(jdbcClient.sql("SELECT cursor_id FROM stop_demand_rebuild_progress WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT sum(sample_count) FROM stop_demand_rebuild_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(1);
        finishRebuild(VEHICLE_204000206);
        assertRebuiltSamples(1);
    }

    @Test
    void 빈_묶음과_같은_시각의_묶음도_끝까지_진행한다() {
        givenStatisticsSample();
        for (int i = 0; i < 40; i++) {
            insertObservationBatch("empty-" + i, RESPONSE_RECEIVED_AT.minusSeconds(1));
        }
        addLateSample("same-time", 0);
        long noResponse = insertObservationBatch("no-response", RESPONSE_RECEIVED_AT.minusSeconds(2));
        jdbcClient.sql("UPDATE observation_batch SET response_received_at=NULL WHERE id=?").param(noResponse).update();
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));
        rebuilder.step(routeVersionId, VEHICLE_204000206);
        rebuilder.step(routeVersionId, VEHICLE_204000206);
        assertThat(jdbcClient.sql("SELECT phase FROM stop_demand_rebuild_progress WHERE route_version_id=?")
            .param(routeVersionId).query(String.class).single()).isEqualTo("SCAN");
        finishRebuild(VEHICLE_204000206);
        assertRebuiltSamples(2);
    }

    @Test
    void 큰_묶음을_나눠_처리하고_롤백_후에도_중복되지_않는다() {
        givenStatisticsSample();
        long arrivalBatch = jdbcClient.sql("SELECT observation_batch_id FROM vehicle_observation WHERE id=(SELECT arrival_observation_id FROM forecast_evaluation WHERE vehicle_observation_id=?)")
            .param(vehicleObservationId).query(Long.class).single();
        for (int i = 1; i < 260; i++) {
            long source = insertObservation(observationBatchId, "large-" + i, i, PASSED_STOP_ORDER);
            long arrival = insertObservation(arrivalBatch, "large-" + i, i, ARRIVAL_STOP_ORDER);
            saveForecasts(List.of(new SeatForecast(source, routeVersionId, TARGET_STOP_ORDER, 1,
                modelDeploymentId, DEMAND_STATISTICS_REVISION, 0.41, 0.38, 12.5, GENERATED_AT)));
            evaluationWriter.complete(List.of(ForecastEvaluation.completed(source, TARGET_STOP_ORDER,
                new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));
        }
        rebuildRequests.request(routeVersionId, RebuildScope.wholeRoute());
        rebuilder.step(routeVersionId, "");
        jdbcClient.sql("SAVEPOINT scan_page").update();
        rebuilder.step(routeVersionId, "");
        assertThat(jdbcClient.sql("SELECT sum(sample_count) FROM stop_demand_rebuild_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(64);
        jdbcClient.sql("ROLLBACK TO SAVEPOINT scan_page").update();
        assertThat(jdbcClient.sql("SELECT group_end_at FROM stop_demand_rebuild_scan WHERE route_version_id=?")
            .param(routeVersionId).query((rs, n) -> rs.getObject(1)).optional()).isEmpty();
        // 진행 위치가 객체가 아닌 DB에 있으므로 새 객체로 같은 트랜잭션에서 재개해도 누락되지 않는다.
        DemandStatisticsRebuilder recreated = new DemandStatisticsRebuilder(rebuilds, rebuildRequests, rebuildStore,
            statisticsStore, samples, qualityAccess, clock);
        recreated.step(routeVersionId, "");
        finishRebuild("");
        assertRebuiltSamples(260);
        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_rebuild_scan WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
    }

    @Test
    void 재계산_시작_후_과거_시각으로_들어온_관측은_다음_누적에_남긴다() {
        givenStatisticsSample();
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));
        rebuilder.step(routeVersionId, VEHICLE_204000206);
        addLateSample("late-insert", -1);
        finishRebuild(VEHICLE_204000206);
        assertRebuiltSamples(1);
        accumulator.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);
        assertRebuiltSamples(2);
    }

    @Test
    void 이전_방식으로_시작한_정정도_기존_커서로_완료한다() {
        givenStatisticsSample();
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));
        rebuilder.step(routeVersionId, VEHICLE_204000206);
        jdbcClient.sql("DELETE FROM stop_demand_rebuild_scan WHERE route_version_id=?").param(routeVersionId).update();
        finishRebuild(VEHICLE_204000206);
        assertRebuiltSamples(1);
    }

    @Test
    void 다른_차량의_품질_변경에도_계산을_이어간다() {
        givenStatisticsSample();
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));
        advanceRebuildTo(VEHICLE_204000206, "CLEAR");
        UUID original = rebuildRequest(VEHICLE_204000206);
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle("other-vehicle"));
        changeEligibility();

        rebuilder.step(routeVersionId, VEHICLE_204000206);

        assertThat(rebuildRequest(VEHICLE_204000206)).isEqualTo(original);
        assertThat(rebuildPhase(VEHICLE_204000206)).isEqualTo("COPY");
        finishRebuild(VEHICLE_204000206);
        assertRebuiltSamples(1);
    }

    @Test
    void 같은_차량의_새_요청은_다시_계산한다() {
        givenStatisticsSample();
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));
        advanceRebuildTo(VEHICLE_204000206, "CLEAR");
        UUID original = rebuildRequest(VEHICLE_204000206);
        addLateSample("same-vehicle-request", 1);
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));

        assertThat(rebuildRequest(VEHICLE_204000206)).isNotEqualTo(original);
        finishRebuild(VEHICLE_204000206);
        assertRebuiltSamples(2);
    }

    @Test
    void 전체_재계산_중에는_차량_계산을_진행하지_않는다() {
        givenStatisticsSample();
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));
        advanceRebuildTo(VEHICLE_204000206, "CLEAR");
        rebuildRequests.request(routeVersionId, RebuildScope.wholeRoute());

        assertThat(rebuilder.step(routeVersionId, VEHICLE_204000206)).isFalse();
        assertThat(rebuildPhase(VEHICLE_204000206)).isEqualTo("CLEAR");
    }

    @Test
    void 전체_재계산_뒤_차량_계산이_새_결과를_지우지_않는다() {
        givenStatisticsSample();
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));
        advanceRebuildTo(VEHICLE_204000206, "CLEAR");
        UUID original = rebuildRequest(VEHICLE_204000206);
        addLateSample("global-refresh", 1);
        rebuildRequests.request(routeVersionId, RebuildScope.wholeRoute());
        finishRebuild("");
        var expected = jdbcClient.sql("SELECT * FROM stop_demand_current_total WHERE route_version_id=? ORDER BY vehicle_id,arrived_hour_start,target_stop_order")
            .param(routeVersionId).query().listOfRows();

        assertRebuiltSamples(2);
        assertThat(rebuildRequest(VEHICLE_204000206)).isNotEqualTo(original);
        finishRebuild(VEHICLE_204000206);

        assertRebuiltSamples(2);
        assertThat(jdbcClient.sql("SELECT * FROM stop_demand_current_total WHERE route_version_id=? ORDER BY vehicle_id,arrived_hour_start,target_stop_order")
            .param(routeVersionId).query().listOfRows()).isEqualTo(expected);
    }

    @Test
    void 전체_계산은_품질이_바뀌면_다시_계산한다() {
        givenStatisticsSample();
        rebuildRequests.request(routeVersionId, RebuildScope.wholeRoute());
        advanceRebuildTo("", "CLEAR");
        jdbcClient.sql("UPDATE vehicle_one_way_trip SET status='EXCLUDED' WHERE start_observation_id=?")
            .param(vehicleObservationId).update();
        rebuildRequests.request(routeVersionId, RebuildScope.vehicle(VEHICLE_204000206));
        changeEligibility();

        finishRebuild("");

        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
    }

    @Test
    void 초기_이관_중에는_기존_정상_통계를_쓰고_완성된_동일_결과로_교체한다() {
        givenStatisticsSample();
        var expected = StopDemandAggregator.aggregate(statistics.readHourlyTotals(routeVersionId, SCORED_AT), clock);
        statistics.append(new DemandStatisticsVersion(routeVersionId, DemandStatisticsVersion.CURRENT_CALCULATION_VERSION,
            1, SCORED_AT, SCORED_AT, expected));

        pipeline.step(routeVersionId);
        assertThat(statistics.readAsOf(routeVersionId, TimeSlot.OTHER,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, PIPELINE_NOW).revision()).isEqualTo(1);
        finishPipeline();

        var actual = statistics.readAsOf(routeVersionId, TimeSlot.OTHER,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, PIPELINE_NOW);
        assertThat(actual.revision()).isEqualTo(2);
        assertThat(actual.cells()).containsExactly(expected.getFirst().cell());
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 누적_세대의_입력을_고정하면_도중에_정산된_자료는_다음_세대에서만_반영된다() {
        givenStatisticsSample();
        finishPipeline();
        when(clock.instant()).thenReturn(PIPELINE_NOW.plusSeconds(21600));
        addLateSample("before-capture", 1);
        for (int i = 0; i < 100; i++) {
            if (pipeline.step(routeVersionId).status() == DemandStatisticsPipeline.Step.Status.STARTED) {
                break;
            }
            if (i == 99) {
                throw new AssertionError("통계 범위 고정에 도달하지 못함");
            }
        }
        addLateSample("after-capture", 2);

        finishPipeline();

        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isEqualTo(1);
        when(clock.instant()).thenReturn(PIPELINE_NOW.plusSeconds(43200));
        finishPipeline();
        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void 발행_도중_실패하면_통계와_완료_표시가_함께_취소된다() {
        givenStatisticsSample();
        for (int i = 0; i < 100; i++) {
            pipeline.step(routeVersionId);
            var phases = jdbcClient.sql("SELECT phase FROM stop_demand_run WHERE route_version_id=?")
                .param(routeVersionId).query(String.class).list();
            if (phases.contains("PUBLISH")) {
                break;
            }
            if (i == 99) {
                throw new AssertionError("발행 단계에 도달하지 못함");
            }
        }
        jdbcClient.sql("ALTER TABLE stop_demand_run ADD CONSTRAINT test_reject_completion CHECK(phase<>'DONE')").update();
        jdbcClient.sql("SAVEPOINT before_publication").update();

        assertThatThrownBy(() -> pipeline.step(routeVersionId)).isInstanceOf(DataIntegrityViolationException.class);
        jdbcClient.sql("ROLLBACK TO SAVEPOINT before_publication").update();

        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_statistics WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM demand_statistics_version WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
        jdbcClient.sql("ALTER TABLE stop_demand_run DROP CONSTRAINT test_reject_completion").update();
        assertThat(pipeline.step(routeVersionId).status()).isEqualTo(DemandStatisticsPipeline.Step.Status.COMPLETED);
    }

    @Test
    void 빈_세대도_완료를_표현하고_완료_이전_관측에는_새_세대를_노출하지_않는다() {
        finishPipeline();
        var ready = statistics.readAsOf(routeVersionId, TimeSlot.MORNING,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, PIPELINE_NOW);
        assertThat(ready.revision()).isEqualTo(1);
        assertThat(ready.cells()).isEmpty();
        jdbcClient.sql("UPDATE demand_statistics_version SET computed_at=? WHERE route_version_id=?")
            .params(PIPELINE_NOW.plusSeconds(30).atOffset(ZoneOffset.UTC), routeVersionId).update();
        assertThat(statistics.readAsOf(routeVersionId, TimeSlot.MORNING,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, PIPELINE_NOW).revision()).isZero();
    }

    @Test
    void 여러_페이지와_날짜의_통계도_전체_SQL의_날짜_균등가중_결과와_같다() {
        for (int stop = 11; stop <= 80; stop++) {
            insertRouteStop(stop);
        }
        for (int day = 0; day < 2; day++) {
            for (int stop = 11; stop <= 80; stop++) {
                addStatisticsAt("page-" + day + "-" + stop, stop,
                    OffsetDateTime.parse("2026-08-20T07:05:00+09:00").plusDays(day),
                    day == 0 ? 24 : 40, day == 0 ? 6 : 20);
            }
        }
        addStatisticsAt("extra-day-two", 11, OffsetDateTime.parse("2026-08-21T07:10:00+09:00"), 40, 10);
        var expected = StopDemandAggregator.aggregate(statistics.readHourlyTotals(routeVersionId, PIPELINE_NOW), clock);

        finishPipeline();

        var actual = statistics.readAsOf(routeVersionId, TimeSlot.MORNING,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, PIPELINE_NOW);
        assertThat(actual.cells()).hasSize(70);
        for (var cell : actual.cells()) {
            var reference = expected.stream().filter(x -> x.cell().stopOrder() == cell.stopOrder())
                .findFirst().orElseThrow().cell();
            assertThat(cell.sampleCount()).isEqualTo(reference.sampleCount());
            assertThat(cell.dayCount()).isEqualTo(reference.dayCount());
            assertThat(cell.averageFillRate()).isCloseTo(reference.averageFillRate(), Offset.offset(1e-12));
            assertThat(cell.averageNetBoardingRate()).isCloseTo(reference.averageNetBoardingRate(), Offset.offset(1e-12));
        }
        assertThat(actual.cells().getFirst().averageFillRate()).isCloseTo(0.7375, Offset.offset(1e-12));
    }

    @ParameterizedTest
    @ValueSource(strings = {"distant", "non_boarding", "missing_seats"})
    void 통계_입력_조건을_벗어난_정산은_정정_재계산에도_넣지_않는다(String excludedReason) {
        givenStatisticsSample();
        settleOutsideStatisticsInput(excludedReason);
        accumulator.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, PIPELINE_NOW);
        var accumulated = currentTotals();

        rebuildRequests.request(routeVersionId, RebuildScope.wholeRoute());
        finishRebuild("");

        assertThat(currentTotals()).isEqualTo(accumulated);
        assertRebuiltSamples(1);
    }

    @Test
    void 이전_형식으로_남은_단계_커서에서도_같은_통계를_발행한다() {
        for (int stop = 11; stop <= 80; stop++) {
            insertRouteStop(stop);
        }
        for (int day = 0; day < 2; day++) {
            for (int stop = 11; stop <= 80; stop++) {
                addStatisticsAt("legacy-" + day + "-" + stop, stop,
                    OffsetDateTime.parse("2026-08-20T07:05:00+09:00").plusDays(day),
                    day == 0 ? 24 : 40, day == 0 ? 6 : 20);
            }
        }
        var expected = StopDemandAggregator.aggregate(statistics.readHourlyTotals(routeVersionId, PIPELINE_NOW), clock);

        finishPipelineLeavingLegacyCursors();

        var actual = statistics.readAsOf(routeVersionId, TimeSlot.MORNING,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, PIPELINE_NOW);
        assertThat(actual.cells()).hasSize(70);
        for (var cell : actual.cells()) {
            var reference = expected.stream().filter(x -> x.cell().stopOrder() == cell.stopOrder())
                .findFirst().orElseThrow().cell();
            assertThat(cell.sampleCount()).isEqualTo(reference.sampleCount());
            assertThat(cell.dayCount()).isEqualTo(reference.dayCount());
            assertThat(cell.averageFillRate()).isCloseTo(reference.averageFillRate(), Offset.offset(1e-12));
            assertThat(cell.averageNetBoardingRate()).isCloseTo(reference.averageNetBoardingRate(), Offset.offset(1e-12));
        }
    }

    private void finishPipelineLeavingLegacyCursors() {
        for (int i = 0; i < 200; i++) {
            if (pipeline.step(routeVersionId).status() == DemandStatisticsPipeline.Step.Status.COMPLETED) {
                return;
            }
            jdbcClient.sql("""
                UPDATE stop_demand_run SET vehicle_cursor='synthetic-bus', hour_cursor=?
                WHERE route_version_id=? AND phase IN ('REDUCE','PUBLISH')
                """).params(OffsetDateTime.parse("2026-08-21T07:00:00+09:00"), routeVersionId).update();
            jdbcClient.sql("""
                UPDATE stop_demand_run SET stop_cursor=80, slot_cursor='morning', day_cursor=DATE '2026-08-21'
                WHERE route_version_id=? AND phase='PUBLISH'
                """).param(routeVersionId).update();
        }
        throw new AssertionError("통계가 200번의 작은 처리 안에 완료되지 않음");
    }

    private void settleOutsideStatisticsInput(String excludedReason) {
        int passed = excludedReason.equals("non_boarding") ? TARGET_STOP_ORDER : PASSED_STOP_ORDER;
        int target = excludedReason.equals("missing_seats") ? TARGET_STOP_ORDER : NEXT_TARGET_STOP_ORDER;
        long source = insertObservation(insertObservationBatch(excludedReason + "-source",
            RESPONSE_RECEIVED_AT.plusSeconds(1)), VEHICLE_204000206, 0, passed);
        long arrival = insertObservation(insertObservationBatch(excludedReason + "-arrival",
            ARRIVAL_RESPONSE_RECEIVED_AT.plusSeconds(60)), VEHICLE_204000206, 0, target);
        saveForecasts(List.of(new SeatForecast(source, routeVersionId, target, target - passed,
            modelDeploymentId, DEMAND_STATISTICS_REVISION, 0.41, 0.38, 12.5, GENERATED_AT)));
        if (excludedReason.equals("non_boarding")) {
            jdbcClient.sql("UPDATE route_stop SET boarding_allowed=false WHERE route_version_id=? AND stop_order=?")
                .params(routeVersionId, target).update();
        }
        if (excludedReason.equals("missing_seats")) {
            jdbcClient.sql("UPDATE vehicle_observation SET remaining_seats=NULL, seat_unknown_reason='NOT_REPORTED' WHERE id=?")
                .param(source).update();
        }
        evaluationWriter.complete(List.of(ForecastEvaluation.completed(source, target,
            new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));
    }

    private List<List<Object>> currentTotals() {
        return jdbcClient.sql("""
            SELECT vehicle_id, arrived_hour_start, target_stop_order, sample_count, arrival_seats_sum, net_boarding_sum
            FROM stop_demand_current_total WHERE route_version_id=?
            ORDER BY vehicle_id, arrived_hour_start, target_stop_order
            """).param(routeVersionId).query((rs, n) -> List.<Object>of(rs.getString(1),
                rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getInt(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6))).list();
    }

    private void assertRebuiltSamples(long expected) {
        assertThat(jdbcClient.sql("SELECT sum(sample_count) FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(expected);
    }

    private UUID rebuildRequest(String vehicle) {
        return jdbcClient.sql("SELECT request_id FROM stop_demand_rebuild_request WHERE route_version_id=? AND vehicle_id=?")
            .params(routeVersionId, vehicle).query(UUID.class).single();
    }

    private String rebuildPhase(String vehicle) {
        return jdbcClient.sql("SELECT phase FROM stop_demand_rebuild_progress WHERE route_version_id=? AND vehicle_id=?")
            .params(routeVersionId, vehicle).query(String.class).optional().orElse("");
    }

    private void advanceRebuildTo(String vehicle, String phase) {
        for (int i = 0; i < 100; i++) {
            if (rebuildPhase(vehicle).equals(phase)) {
                return;
            }
            assertThat(rebuilder.step(routeVersionId, vehicle)).isTrue();
        }
        throw new AssertionError("정정이 목표 단계에 도달하지 않음: " + phase);
    }

    private void finishRebuild(String vehicle) {
        for (int i = 0; i < 100; i++) {
            if (!rebuilder.step(routeVersionId, vehicle)) {
                return;
            }
        }
        throw new AssertionError("정정이 100번의 작은 처리 안에 완료되지 않음");
    }

    private void finishPipeline() {
        for (int i = 0; i < 200; i++) {
            if (pipeline.step(routeVersionId).status() == DemandStatisticsPipeline.Step.Status.COMPLETED) {
                return;
            }
        }
        throw new AssertionError("통계가 200번의 작은 처리 안에 완료되지 않음");
    }

    private void addStatisticsAt(String key, int stop, OffsetDateTime at, int before, int after) {
        long sourceBatch = insertObservationBatch(key + "-source", at.minusSeconds(15));
        long source = insertObservation(sourceBatch, "synthetic-bus", 0, stop - 1);
        long arrivalBatch = insertObservationBatch(key + "-arrival", at);
        long arrival = insertObservation(arrivalBatch, "synthetic-bus", 0, stop);
        jdbcClient.sql("UPDATE vehicle_observation SET remaining_seats=? WHERE id=?").params(before, source).update();
        jdbcClient.sql("UPDATE vehicle_observation SET remaining_seats=? WHERE id=?").params(after, arrival).update();
        saveForecasts(List.of(new SeatForecast(source, routeVersionId, stop, 1,
            modelDeploymentId, DEMAND_STATISTICS_REVISION, 0.41, 0.38, 12.5, at.minusSeconds(14).toInstant())));
        evaluationWriter.complete(List.of(ForecastEvaluation.completed(source, stop,
            new ArrivalLabel.Settled(arrival, after), at.plusSeconds(60).toInstant())));
    }

    private void addLateSample(String key, int seconds) {
        long batch = insertObservationBatch(key, RESPONSE_RECEIVED_AT.plusSeconds(seconds));
        long source = insertObservation(batch, VEHICLE_204000206, 0, PASSED_STOP_ORDER);
        saveForecasts(List.of(new SeatForecast(source, routeVersionId, TARGET_STOP_ORDER, 1,
            modelDeploymentId, DEMAND_STATISTICS_REVISION, 0.41, 0.38, 12.5, GENERATED_AT)));
        long arrival = jdbcClient.sql("SELECT arrival_observation_id FROM forecast_evaluation WHERE vehicle_observation_id=? AND target_stop_order=?")
            .params(vehicleObservationId, TARGET_STOP_ORDER).query(Long.class).single();
        evaluationWriter.complete(List.of(ForecastEvaluation.completed(source, TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));
    }

    private void givenStatisticsSample() {
        long arrival = insertArrivalObservation();
        saveForecasts(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));
        evaluationWriter.complete(List.of(ForecastEvaluation.completed(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));
    }

    private int pendingSampleCount() {
        return jdbcClient.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE prediction_observation_id = ?")
            .param(vehicleObservationId).query(Integer.class).single();
    }

    private void saveForecasts(List<SeatForecast> forecasts) {
        ForecastEvaluationTestData.saveForecasts(jdbcClient, forecasts);
    }

    private SeatForecast forecastOf(
        final int targetStopOrder,
        final int stopsToTarget,
        Instant generatedAt
    ) {
        return new SeatForecast(
            vehicleObservationId,
            routeVersionId,
            targetStopOrder,
            stopsToTarget,
            modelDeploymentId,
            DEMAND_STATISTICS_REVISION,
            0.41,
            0.38,
            12.5,
            generatedAt);
    }

    private long insertArrivalObservation() {
        final long arrivalBatchId = insertObservationBatch("2026-08-19T11:20", ARRIVAL_RESPONSE_RECEIVED_AT);
        return insertObservation(arrivalBatchId, VEHICLE_204000206, 0, ARRIVAL_STOP_ORDER);
    }

    private long insertRoute() {
        return jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(ROUTE_204000057, SOURCE_ID, ROUTE_204000057, "3330", "범계역", "강남역")
            .query(Long.class)
            .single();
    }

    private long insertRouteVersion(
        final long routeId
    ) {
        return jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?)
                RETURNING id
                """)
            .params(routeId, CONTENT_DIGEST, RESPONSE_RECEIVED_AT)
            .query(Long.class)
            .single();
    }

    private void insertRouteStop(
        final int stopOrder
    ) {
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(routeVersionId, stopOrder, stopIdOf(stopOrder), "정류소 " + stopOrder, "UP", true)
            .update();
    }

    private long insertModelDeployment() {
        return jdbcClient.sql("""
                INSERT INTO model_deployment (
                    deployment_key, release_id, model_key, model_version, bundle_digest,
                    prediction_target_version, calculation_version, supported_scope_digest,
                    data_until, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                UUID.fromString("0f8b4c1e-6f2a-4c3d-9e51-2b7a8c4d5e6f"),
                "2026-08-19-1", "seat-full-chance", "1.4.0", CONTENT_DIGEST,
                "SEAT_FULL_CHANCE_V1", "CALCULATION_V1", CONTENT_DIGEST,
                RESPONSE_RECEIVED_AT, "ACTIVE"
            )
            .query(Long.class)
            .single();
    }

    private long insertObservationBatch(
        String attemptKey,
        OffsetDateTime responseReceivedAt
    ) {
        return jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, response_received_at, outcome,
                    normalization_version, collection_strategy_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                routeVersionId, responseReceivedAt, 1, ROUTE_204000057 + "-" + attemptKey,
                responseReceivedAt, responseReceivedAt, "SUCCESS_ROWS",
                NORMALIZATION_VERSION, COLLECTION_STRATEGY_VERSION
            )
            .query(Long.class)
            .single();
    }

    private long insertObservation(
        final long batchId,
        String vehicleId,
        final int sourceRowNumber,
        final int stopOrder
    ) {
        return ConfirmedTripFixture.include(jdbcClient, jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                batchId, routeVersionId, sourceRowNumber,
                vehicleId, stopOrder, stopIdOf(stopOrder), stopOrder,
                RUNNING_STATE_DEPARTED, SEATS_LEFT
            )
            .query(Long.class)
            .single());
    }

    private static String stopIdOf(
        final int stopOrder
    ) {
        return "20500%04d".formatted(stopOrder);
    }

    private void changeEligibility() {
        RouteDataQuality quality = qualities.findForUpdate(routeVersionId);
        quality.changeEligibility();
        qualities.save(quality);
    }
}
