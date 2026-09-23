package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.domain.model.ForecastRuntime;
import com.gustler.backend.forecasting.domain.model.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.model.SeatDistribution;
import com.gustler.backend.forecasting.domain.model.SeatForecastInput;
import com.gustler.backend.forecasting.domain.model.SeatForecastModel;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.domain.model.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.SupportedForecastScope;
import com.gustler.backend.support.ConfirmedTripFixture;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 배치의 예보 발행과 저장 결과를 함께 검증한다.
 *
 * <p>테스트용 {@link SeatForecastModel}이 일정한 값을 반환하게 해 발행 결과를 확인한다.
 *
 * <p>업무 모듈의 테스트 구성으로 실행한다. {@link ForecastBatchWriter}는 테스트 트랜잭션에
 * 참여하므로 테스트가 끝나면 저장 결과도 함께 롤백한다.
 */
@ForecastingIntegrationTest
@Transactional
@Import(PublishPendingForecastsIntegrationTest.FixedSeatForecastModel.class)
class PublishPendingForecastsIntegrationTest {

    /** 판마다 남는 두 판본. 기본값이 없어서 픽스처가 직접 넣는다. */
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String COLLECTION_STRATEGY_VERSION = "adaptive-kst-v1.0.1";

    /** 지나감(2)으로 넣어서 통과 순번이 상류 순번과 같다. 관측 시각 열은 SAL-84 가 지웠다. */
    private static final int RUNNING_STATE_DEPARTED = 2;
    private static final int SEATS_LEFT = 12;
    private static final String SEAT_UNKNOWN_REASON = "REPORTED_UNKNOWN";

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204000207 = "204000207";
    private static final String VEHICLE_204000208 = "204000208";

    /** 예보를 붙일 수 있는 판의 결말. 차가 한 대도 없던 판은 SUCCESS_EMPTY 로 남는다. */
    private static final String OUTCOME_WITH_ROWS = "SUCCESS_ROWS";
    private static final String OUTCOME_WITHOUT_ROWS = "SUCCESS_EMPTY";

    /** 노선 판본의 정류장은 1번부터 5번까지 다섯 개다. 5번이 종점이다. */
    private static final int FIRST_STOP_ORDER = 1;
    private static final int LAST_STOP_ORDER = 5;

    /** 기준 차량이 지나온 정류장. 이 차량의 예보 대상은 4번과 5번 둘뿐이다. */
    private static final int PASSED_STOP_ORDER = 3;
    private static final List<Integer> STOP_ORDERS_AHEAD_OF_PASSED_STOP = List.of(4, 5);
    private static final int TARGET_COUNT_AHEAD_OF_PASSED_STOP = LAST_STOP_ORDER - PASSED_STOP_ORDER;

    /** 아직 앞이 많이 남은 차량. 예보 대상이 2·3·4·5번 넷이다. */
    private static final int EARLY_PASSED_STOP_ORDER = 1;
    private static final int TARGET_COUNT_AHEAD_OF_EARLY_PASSED_STOP = LAST_STOP_ORDER - EARLY_PASSED_STOP_ORDER;

    /** 승차할 수 없게 바꿀 정류장. 이 순번이 빠지면 기준 차량의 대상은 5번 하나만 남는다. */
    private static final int BOARDING_BLOCKED_STOP_ORDER = 4;

    private static final int FIRST_SOURCE_ROW_NUMBER = 0;
    private static final int SECOND_SOURCE_ROW_NUMBER = 1;
    private static final int THIRD_SOURCE_ROW_NUMBER = 2;

    private static final long NO_FORECAST_ROW = 0L;

    /** 판이 상류 응답을 받은 시각. 관측 시각의 권위가 여기 있다. */
    private static final OffsetDateTime RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final OffsetDateTime EMPTY_RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:16:04.911+09:00");
    private static final OffsetDateTime PREVIOUSLY_PUBLISHED_AT =
        OffsetDateTime.parse("2026-08-19T11:14:07.000+09:00");

    // 품질 잠금은 전용 테스트에서 검증하고, 여기서는 발행에 기록할 품질 버전을 고정한다.
    @MockitoBean
    private RouteDataQualityAccess routeDataQuality;

    @Autowired
    private PublishPendingForecastsService forecastJob;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private RecordingSeatForecastModel seatForecastModel;

    private long routeVersionId;
    private long modelDeploymentId;
    private long observationBatchId;
    private long vehicleObservationId;

    @BeforeEach
    void 노선_판본과_정류장과_도는_배포와_관측을_먼저_저장한다() {
        seatForecastModel.forgetReceivedInputs();
        when(routeDataQuality.lock(anyLong())).thenReturn(1L);
        final long routeId = insertRoute();
        jdbcClient.sql("INSERT INTO route_data_quality(route_id, quality_revision) VALUES (?, 1)")
            .param(routeId).update();
        routeVersionId = insertRouteVersion(routeId);
        for (int stopOrder = FIRST_STOP_ORDER; stopOrder <= LAST_STOP_ORDER; stopOrder++) {
            insertRouteStop(stopOrder);
        }
        modelDeploymentId = insertActiveModelDeployment();
        observationBatchId = insertObservationBatch("11:14", RESPONSE_RECEIVED_AT, OUTCOME_WITH_ROWS);
        vehicleObservationId = insertObservationWithKnownSeats(
            VEHICLE_204000206, FIRST_SOURCE_ROW_NUMBER, PASSED_STOP_ORDER);
    }

    @Test
    void 예보가_안_붙은_판의_차량마다_앞_정류장_예보가_쌓인다() {
        // given
        final long earlyVehicleObservationId = insertObservationWithKnownSeats(
            VEHICLE_204000207, SECOND_SOURCE_ROW_NUMBER, EARLY_PASSED_STOP_ORDER);

        // when
        forecastJob.writeForecasts();

        // then
        List<VehicleForecastCount> actual = readForecastCounts();
        assertThat(actual).containsExactly(
            new VehicleForecastCount(vehicleObservationId, TARGET_COUNT_AHEAD_OF_PASSED_STOP),
            new VehicleForecastCount(earlyVehicleObservationId, TARGET_COUNT_AHEAD_OF_EARLY_PASSED_STOP));
    }

    @Test
    void 예보를_발행하면_발행_정보와_입력_확정_시각을_기록한다() {
        // when
        forecastJob.writeForecasts();

        // then
        StoredPublication publication = readPublication(observationBatchId);
        assertThat(publication.sourceAttemptNumber()).isEqualTo(1);
        assertThat(publication.modelDeploymentId()).isEqualTo(modelDeploymentId);
        assertThat(publication.statisticsRevision())
            .isEqualTo(seatForecastModel.firstReceivedInput().statistics().revision());
        assertThat(publication.qualityRevision()).isEqualTo(1);
        assertThat(publication.observedAt()).isEqualTo(RESPONSE_RECEIVED_AT.toInstant());
        assertThat(publication.predictionCount()).isEqualTo(TARGET_COUNT_AHEAD_OF_PASSED_STOP);
        assertThat(readDistinctGeneratedAt()).containsExactly(publication.generatedAt());
        assertThat(publication.publishedAt()).isNotNull();
        assertThat(readInputConfirmedAt(observationBatchId)).isNotNull();
    }

    @Test
    void 이미_예보가_붙은_판은_다시_안_연다() {
        // given
        insertLegacyPublication(observationBatchId, PREVIOUSLY_PUBLISHED_AT);

        // when
        forecastJob.writeForecasts();

        // then
        List<Integer> actual = readTargetStopOrders();
        assertThat(actual).isEmpty();
    }

    @Test
    void 잔여석을_아는_차량만_예보한다() {
        // given
        insertObservationWithUnknownSeats(VEHICLE_204000207, SECOND_SOURCE_ROW_NUMBER, PASSED_STOP_ORDER);

        // when
        forecastJob.writeForecasts();

        // then
        List<Long> actual = readForecastVehicleObservationIds();
        assertThat(actual).containsExactly(vehicleObservationId);
    }

    @Test
    void 승차할_수_있는_정류장에만_예보_행이_생긴다() {
        // given
        blockBoardingAt(BOARDING_BLOCKED_STOP_ORDER);

        // when
        forecastJob.writeForecasts();

        // then
        List<Integer> actual = readTargetStopOrders();
        assertThat(actual).containsExactly(LAST_STOP_ORDER);
    }

    @Test
    void 한_판의_예보는_전부_같은_배포를_가리킨다() {
        // given
        insertObservationWithKnownSeats(VEHICLE_204000207, SECOND_SOURCE_ROW_NUMBER, EARLY_PASSED_STOP_ORDER);

        // when
        forecastJob.writeForecasts();

        // then
        List<Long> actual = readModelDeploymentIds();
        assertThat(actual).containsOnly(modelDeploymentId);
    }

    @Test
    void 한_판의_예보는_전부_같은_계산_시각을_갖는다() {
        // given
        insertObservationWithKnownSeats(VEHICLE_204000207, SECOND_SOURCE_ROW_NUMBER, EARLY_PASSED_STOP_ORDER);
        insertObservationWithKnownSeats(VEHICLE_204000208, THIRD_SOURCE_ROW_NUMBER, FIRST_STOP_ORDER);

        // when
        forecastJob.writeForecasts();

        // then
        List<Instant> actual = readDistinctGeneratedAt();
        assertThat(actual).hasSize(1);
    }

    @Test
    void 차량이_없는_수집_배치도_사용한_모델과_함께_발행한다() {
        // given
        final long emptyBatchId =
            insertObservationBatch("11:16", EMPTY_RESPONSE_RECEIVED_AT, OUTCOME_WITHOUT_ROWS);

        // when
        forecastJob.writeForecasts();

        // then
        StoredPublication publication = readPublication(emptyBatchId);
        assertThat(publication.modelDeploymentId()).isEqualTo(modelDeploymentId);
        assertThat(publication.qualityRevision()).isEqualTo(1);
        assertThat(publication.predictionCount()).isZero();
        assertThat(publication.observedAt()).isEqualTo(EMPTY_RESPONSE_RECEIVED_AT.toInstant());
        assertThat(publication.generatedAt()).isNotNull();
        assertThat(publication.publishedAt()).isNotNull();
        assertThat(readInputConfirmedAt(emptyBatchId)).isNotNull();
    }

    @Test
    void 도는_배포가_없으면_판을_하나도_안_연다() {
        // given
        deleteModelDeployment();

        // when
        forecastJob.writeForecasts();

        // then
        UntouchedBatch actual =
            new UntouchedBatch(countForecasts(), readPublishedAt(observationBatchId));
        assertThat(actual).isEqualTo(new UntouchedBatch(NO_FORECAST_ROW, null));
    }

    @Test
    void 여정에_남은_정류장까지만_예보한다() {
        // when
        forecastJob.writeForecasts();

        // then
        List<Integer> actual = readTargetStopOrders();
        assertThat(actual).containsExactlyElementsOf(STOP_ORDERS_AHEAD_OF_PASSED_STOP);
    }

    private List<Integer> readTargetStopOrders() {
        return jdbcClient.sql("""
                SELECT forecast.target_stop_order
                FROM seat_forecast forecast
                JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
                WHERE observation.observation_batch_id = ?
                ORDER BY forecast.target_stop_order
                """)
            .param(observationBatchId)
            .query(Integer.class)
            .list();
    }

    private List<Long> readForecastVehicleObservationIds() {
        return jdbcClient.sql("""
                SELECT DISTINCT forecast.vehicle_observation_id
                FROM seat_forecast forecast
                JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
                WHERE observation.observation_batch_id = ?
                ORDER BY forecast.vehicle_observation_id
                """)
            .param(observationBatchId)
            .query(Long.class)
            .list();
    }

    private List<VehicleForecastCount> readForecastCounts() {
        return jdbcClient.sql("""
                SELECT forecast.vehicle_observation_id, count(*) AS forecast_count
                FROM seat_forecast forecast
                JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
                WHERE observation.observation_batch_id = ?
                GROUP BY forecast.vehicle_observation_id
                ORDER BY forecast.vehicle_observation_id
                """)
            .param(observationBatchId)
            .query((resultSet, rowNumber) -> new VehicleForecastCount(
                resultSet.getLong("vehicle_observation_id"),
                resultSet.getLong("forecast_count")))
            .list();
    }

    @Test
    void 예보에_남는_세대_번호는_모델에_넘긴_셀_통계의_세대다() {
        // when
        forecastJob.writeForecasts();

        // then 값과 세대 번호를 따로 읽으면 그 사이 집계 교체에 두 값이 갈린다
        final int actual = seatForecastModel.firstReceivedInput().statistics().revision();
        assertThat(readDemandStatisticsRevisions()).isNotEmpty().containsOnly(actual);
    }

    @Test
    void 설계행렬의_시간대와_셀_통계의_시간대가_같다() {
        // when
        forecastJob.writeForecasts();

        // then 두 곳이 각자 정하면 경계에서 한 예보 행이 두 시간대를 섞는다
        SeatForecastInput actual = seatForecastModel.firstReceivedInput();
        assertThat(actual.timeSlot()).isEqualTo(actual.statistics().timeSlot());
    }

    private List<Integer> readDemandStatisticsRevisions() {
        return jdbcClient.sql("""
                SELECT forecast.demand_statistics_revision
                FROM seat_forecast forecast
                JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
                WHERE observation.observation_batch_id = ?
                """)
            .param(observationBatchId)
            .query(Integer.class)
            .list();
    }

    private List<Long> readModelDeploymentIds() {
        return jdbcClient.sql("""
                SELECT forecast.model_deployment_id
                FROM seat_forecast forecast
                JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
                WHERE observation.observation_batch_id = ?
                """)
            .param(observationBatchId)
            .query(Long.class)
            .list();
    }

    private List<Instant> readDistinctGeneratedAt() {
        return jdbcClient.sql("""
                SELECT DISTINCT forecast.generated_at
                FROM seat_forecast forecast
                JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
                WHERE observation.observation_batch_id = ?
                """)
            .param(observationBatchId)
            .query((resultSet, rowNumber) ->
                instantOf(resultSet.getObject("generated_at", OffsetDateTime.class)))
            .list();
    }

    private long countForecasts() {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM seat_forecast forecast
                JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
                WHERE observation.observation_batch_id = ?
                """)
            .param(observationBatchId)
            .query(Long.class)
            .single();
    }

    /** 아직 발행하지 않은 수집 배치는 발행 기록이 없다. */
    private Instant readPublishedAt(
        final long batchId
    ) {
        return jdbcClient.sql("""
                SELECT published_at
                FROM forecast_publication
                WHERE source_batch_id = ?
                """)
            .param(batchId)
            .query((resultSet, rowNumber) ->
                instantOf(resultSet.getObject("published_at", OffsetDateTime.class)))
            .optional()
            .orElse(null);
    }

    private StoredPublication readPublication(final long batchId) {
        return jdbcClient.sql("""
                SELECT source_attempt_number, model_deployment_id, demand_statistics_revision,
                       quality_revision, observed_at, generated_at, published_at, prediction_count
                FROM forecast_publication WHERE source_batch_id = ?
                """)
            .param(batchId)
            .query((resultSet, rowNumber) -> new StoredPublication(
                resultSet.getInt("source_attempt_number"),
                resultSet.getLong("model_deployment_id"),
                resultSet.getInt("demand_statistics_revision"),
                resultSet.getLong("quality_revision"),
                instantOf(resultSet.getObject("observed_at", OffsetDateTime.class)),
                instantOf(resultSet.getObject("generated_at", OffsetDateTime.class)),
                instantOf(resultSet.getObject("published_at", OffsetDateTime.class)),
                resultSet.getInt("prediction_count")))
            .single();
    }

    private Instant readInputConfirmedAt(final long batchId) {
        return jdbcClient.sql("SELECT input_confirmed_at FROM observation_batch WHERE id = ?")
            .param(batchId)
            .query((resultSet, rowNumber) ->
                instantOf(resultSet.getObject("input_confirmed_at", OffsetDateTime.class)))
            .single();
    }

    @Test
    void 배치는_모델에_셀_통계와_정류장_목록까지_모아_넘긴다() {
        // when
        forecastJob.writeForecasts();

        // then 모델이 DB 를 안 읽으므로 배치가 재료를 다 챙겨야 한다
        SeatForecastInput actual = seatForecastModel.firstReceivedInput();
        assertThat(actual.stops().largestStopOrder()).isEqualTo(LAST_STOP_ORDER);
    }

    @Test
    void 배치가_넘기는_재료의_셀_통계는_그_노선_판본의_것이다() {
        // when
        forecastJob.writeForecasts();

        // then
        SeatForecastInput actual = seatForecastModel.firstReceivedInput();
        assertThat(actual.statistics().routeVersionId()).isEqualTo(routeVersionId);
    }

    @Test
    void 배치가_넘기는_재료에_그_차량이_보여_준_최대_잔여석이_들어_있다() {
        // when
        forecastJob.writeForecasts();

        // then
        SeatForecastInput actual = seatForecastModel.firstReceivedInput();
        assertThat(actual.maximumSeatsEverObserved()).isEqualTo(SEATS_LEFT);
    }

    private void insertLegacyPublication(
        final long batchId,
        OffsetDateTime completedAt
    ) {
        jdbcClient.sql("""
                UPDATE observation_batch SET input_confirmed_at = ? WHERE id = ?
                """)
            .params(completedAt, batchId)
            .update();
        jdbcClient.sql("""
                INSERT INTO forecast_publication (
                    source_batch_id, source_attempt_number, route_version_id, observed_at,
                    published_at, prediction_count, provenance
                )
                SELECT id, attempt_number, route_version_id, response_received_at, ?, 0, 'LEGACY_UNKNOWN'
                FROM observation_batch WHERE id = ?
                """)
            .params(completedAt, batchId)
            .update();
    }

    private void blockBoardingAt(
        final int stopOrder
    ) {
        jdbcClient.sql("""
                UPDATE route_stop
                SET boarding_allowed = false
                WHERE route_version_id = ?
                  AND stop_order = ?
                """)
            .params(routeVersionId, stopOrder)
            .update();
    }

    private void deleteModelDeployment() {
        jdbcClient.sql("""
                DELETE FROM model_deployment
                WHERE id = ?
                """)
            .param(modelDeploymentId)
            .update();
    }

    private static Instant instantOf(
        OffsetDateTime timestamp
    ) {
        return timestamp == null ? null : timestamp.toInstant();
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

    private long insertActiveModelDeployment() {
        return jdbcClient.sql("""
                INSERT INTO model_deployment (
                    deployment_key, release_id, model_key, model_version, bundle_digest,
                    prediction_target_version, calculation_version, supported_scope_digest,
                    data_until, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                UUID.fromString("7c1f5a3d-2e94-4b18-8a6c-0d3f9b2e7a41"),
                "2026-08-19-1", "seat-full-chance", "1.4.0", CONTENT_DIGEST,
                "SEAT_FULL_CHANCE_V1", "CALCULATION_V1", CONTENT_DIGEST,
                RESPONSE_RECEIVED_AT, "ACTIVE"
            )
            .query(Long.class)
            .single();
    }

    private long insertObservationBatch(
        String attemptKey,
        OffsetDateTime responseReceivedAt,
        String outcome
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
                responseReceivedAt, responseReceivedAt, outcome,
                NORMALIZATION_VERSION, COLLECTION_STRATEGY_VERSION
            )
            .query(Long.class)
            .single();
    }

    private long insertObservationWithKnownSeats(
        String vehicleId,
        final int sourceRowNumber,
        final int passedStopOrder
    ) {
        return insertObservation(vehicleId, sourceRowNumber, passedStopOrder, SEATS_LEFT, null);
    }

    private long insertObservationWithUnknownSeats(
        String vehicleId,
        final int sourceRowNumber,
        final int passedStopOrder
    ) {
        return insertObservation(vehicleId, sourceRowNumber, passedStopOrder, null, SEAT_UNKNOWN_REASON);
    }

    /**
     * 관측 한 행. 운행 상태가 지나감이라 상류 순번과 통과 순번이 같다.
     *
     * <p>잔여석과 모르는 사유는 둘 중 하나만 채운다. 스키마가 둘 다 채우는 것도 둘 다 비우는 것도 막는다.
     */
    private long insertObservation(
        String vehicleId,
        final int sourceRowNumber,
        final int passedStopOrder,
        Integer remainingSeats,
        String seatUnknownReason
    ) {
        return ConfirmedTripFixture.include(jdbcClient, jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats, seat_unknown_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                observationBatchId, routeVersionId, sourceRowNumber,
                vehicleId, passedStopOrder, stopIdOf(passedStopOrder), passedStopOrder,
                RUNNING_STATE_DEPARTED, remainingSeats, seatUnknownReason
            )
            .query(Long.class)
            .single());
    }

    private static String stopIdOf(
        final int stopOrder
    ) {
        return "20500%04d".formatted(stopOrder);
    }

    private record StoredPublication(
        int sourceAttemptNumber,
        long modelDeploymentId,
        int statisticsRevision,
        long qualityRevision,
        Instant observedAt,
        Instant generatedAt,
        Instant publishedAt,
        int predictionCount
    ) {
    }

    /** 차량 한 대에 쌓인 예보 줄 수. */
    private record VehicleForecastCount(
        long vehicleObservationId,
        long forecastCount
    ) {
    }

    /** 발행하지 않은 수집 배치의 예측 건수와 발행 기록을 확인한다. */
    private record UntouchedBatch(
        long forecastCount,
        Instant publishedAt
    ) {
    }

    /**
     * 늘 같은 값을 내고 받은 재료를 적어 두는 좌석 예보 모델.
     *
     * <p>계수 번들이 없어 {@link SeatForecastModel} 구현이 아직 없다. 배치가 판을 열려면 모델 빈이
     * 있어야 해서 테스트가 하나 올린다. 값이 고정이라 예보 행의 확률과 기대 잔여석이 실행마다 같다.
     *
     * <p>받은 재료를 적어 두는 이유는 배치가 모델에 무엇을 넘기는지가 이 티켓에서 바뀌었기 때문이다.
     * 예보 행만 보면 재료가 빈 채로 넘어가도 알 수 없다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedSeatForecastModel {

        private static final Instant DATA_UNTIL = Instant.parse("2026-08-30T14:59:56Z");

        /**
         * 픽스처의 판을 신선도 창 안에 두는 시각. 두 판 중 늦은 것보다 1분쯤 뒤다.
         *
         * <p>실시계로 두면 고정된 과거인 픽스처가 기본 창 5분 밖이라 판이 하나도 안 열린다.
         * 창을 넓혀서 비키면 그 값이 운영에서 쓸 수 없는 크기가 된다.
         */
        private static final Instant JUST_AFTER_FIXTURE = Instant.parse("2026-08-19T02:17:00Z");

        @Bean
        RecordingSeatForecastModel seatForecastModel() {
            return new RecordingSeatForecastModel();
        }

        /** 이름을 clock 으로 두면 ClockConfig 의 빈과 부딪힌다. 덮어쓰기가 꺼져 있어 이름을 가른다. */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(JUST_AFTER_FIXTURE, ZoneId.of("Asia/Seoul"));
        }

        /**
         * 도는 배포와 모델을 한 덩어리로 내는 자리를 가짜로 바꾼다.
         *
         * <p>계수 파일이 없어서 진짜 자리는 아무것도 안 낸다. 배치가 무엇을 모델에 넘기는지를 보려면
         * 그 자리가 배포 행과 이 가짜 모델을 묶어 내야 한다.
         */
        @Bean
        @Primary
        ForecastRuntime forecastRuntime(
            ModelDeploymentRepository deployments,
            RecordingSeatForecastModel model
        ) {
            return () -> deployments.findActive().map(deployment -> new RuntimeSnapshot(
                deployment,
                new SupportedForecastScope(List.of("1650", "3330")),
                model,
                DATA_UNTIL));
        }
    }

    static class RecordingSeatForecastModel implements SeatForecastModel {

        private static final double FULL_CHANCE_RAW = 0.4;

        /** 0석 남을 확률 0.4, 1석 남을 확률 0.6. 합이 1이라 분포 규칙을 지킨다. */
        private static final List<Double> CHANCE_BY_SEATS = List.of(0.4, 0.6);

        private final List<SeatForecastInput> receivedInputs = new ArrayList<>();

        @Override
        public SeatForecastResult predict(
            SeatForecastInput input
        ) {
            receivedInputs.add(input);
            return new SeatForecastResult(new SeatDistribution(CHANCE_BY_SEATS), FULL_CHANCE_RAW);
        }

        void forgetReceivedInputs() {
            receivedInputs.clear();
        }

        SeatForecastInput firstReceivedInput() {
            return receivedInputs.getFirst();
        }
    }
}
