package com.gustler.backend.observations.application;

import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.ObservedSeatValue;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import com.gustler.backend.observations.domain.CollectionAttemptToken;
import com.gustler.backend.observations.domain.ConflictingCollectionResultException;
import com.gustler.backend.observations.domain.InputAlreadyConfirmedException;
import com.gustler.backend.observations.domain.StaleCollectionAttemptException;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.quota.domain.CallQuota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.gbis.api.GbisLocationResult.GbisSystemError;
import com.gustler.backend.gbis.api.GbisLocationResult.NoResponse;
import com.gustler.backend.gbis.api.GbisLocationResult.NoVehicles;
import com.gustler.backend.gbis.api.GbisLocationResult.Success;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.support.IntegrationTest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예약이 제 트랜잭션에서 커밋되므로 @Transactional 을 붙이면 장부가 정리되지 않는다.
 * 매번 비우고 시작한다.
 */
@IntegrationTest
@Import(ObservationBatchLedgerTest.QualityFailureConfiguration.class)
class ObservationBatchLedgerTest {

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String ATTEMPT_KEY = "204000057-2026-08-19T11:14";
    private static final String QUERY_TIME = "2026-08-19 11:14:04.9";
    private static final String VEHICLE_ID = "204000206";
    private static final String STOP_ID = "205000217";

    private static final OffsetDateTime SCHEDULED_AT = OffsetDateTime.parse("2026-08-19T11:14:00+09:00");
    private static final OffsetDateTime RESERVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.700+09:00");
    private static final OffsetDateTime REQUESTED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.800+09:00");
    private static final OffsetDateTime RESPONSE_RECEIVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final LocalDate KOREA_8_19 = LocalDate.of(2026, 8, 19);

    private static final int ALREADY_USED_UP = 3;
    private static final long MISSING_ROUTE_VERSION_ID = 9_999_999L;

    /** 한국 시각 2026-08-28 23:59:59 와 2026-08-29 00:00:00. 세계 표준시로는 같은 날이다. */
    private static final OffsetDateTime KOREA_8_28_LATE_NIGHT = OffsetDateTime.parse("2026-08-28T14:59:59Z");
    private static final OffsetDateTime KOREA_8_29_MIDNIGHT = OffsetDateTime.parse("2026-08-28T15:00:00Z");
    private static final LocalDate KOREA_8_29 = LocalDate.of(2026, 8, 29);

    @Autowired
    private ObservationBatchLedger ledger;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private CollectionInputs collectionInputs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FailingQualityHook failingQualityHook;

    private long routeVersionId;

    @BeforeEach
    void 노선과_판본을_먼저_저장한다() {
        routeVersionId = insertRouteVersion(insertRoute());
    }

    @AfterEach
    void 쌓인_것을_비운다() {
        failingQualityHook.fail = false;
        jdbcClient.sql("""
                TRUNCATE vehicle_observation, observation_batch, route_stop, route_version, route,
                    daily_call_quota RESTART IDENTITY CASCADE
                """)
            .update();
    }

    @Test
    void 자리를_잡은_판은_RESERVED로_남는다() {
        // when
        final long batchId = ledger.reserve(plan(), RESERVED_AT).batchId();

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("RESERVED");
    }

    @Test
    void 자리를_잡은_판은_한도를_한_번_쓴다() {
        // when
        ledger.reserve(plan(), RESERVED_AT);

        // then
        assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(1);
    }

    @Test
    void 자리를_못_잡은_판은_NOT_RESERVED로_남는다() {
        // given
        useUpTheQuota();

        // when
        final long batchId = ledger.reserve(plan(), RESERVED_AT).batchId();

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("NOT_RESERVED");
    }

    @Test
    void 자리를_못_잡은_판의_실패_사유는_LOCAL_QUOTA_EXHAUSTED다() {
        // given
        useUpTheQuota();

        // when
        final long batchId = ledger.reserve(plan(), RESERVED_AT).batchId();

        // then
        assertThat(failureCodeOf(batchId)).isEqualTo("LOCAL_QUOTA_EXHAUSTED");
    }

    @Test
    void 자리를_못_잡은_판은_보낸_시각이_비어_있다() {
        // given
        useUpTheQuota();

        // when
        final long batchId = ledger.reserve(plan(), RESERVED_AT).batchId();

        // then
        assertThat(requestedAtOf(batchId)).isNull();
    }

    @Test
    void 보내기_직전의_판은_DISPATCHING으로_바뀐다() {
        // given
        CollectionAttemptToken token = ledger.reserve(plan(), RESERVED_AT).token();
        final long batchId = token.batchId();

        // when
        ledger.markDispatching(token, RESERVED_AT, REQUESTED_AT);

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("DISPATCHING");
    }

    @Test
    void 보내기_직전의_판은_보낸_시각을_남긴다() {
        // given
        CollectionAttemptToken token = ledger.reserve(plan(), RESERVED_AT).token();
        final long batchId = token.batchId();

        // when
        ledger.markDispatching(token, RESERVED_AT, REQUESTED_AT);

        // then
        assertThat(requestedAtOf(batchId)).isEqualTo(REQUESTED_AT);
    }

    @Test
    void 보내지_않고_그만둔_판은_ABANDONED_BEFORE_SEND로_남는다() {
        // given
        CollectionAttemptToken token = ledger.reserve(plan(), RESERVED_AT).token();
        final long batchId = token.batchId();

        // when
        ledger.abandonBeforeSend(token);

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("ABANDONED_BEFORE_SEND");
    }

    @Test
    void 보냈는데_응답이_오지_않은_판은_UNKNOWN_AFTER_DISPATCH로_남는다() {
        // given
        CollectionAttemptToken token = dispatchedBatch();
        final long batchId = token.batchId();

        // when
        ledger.conclude(token, new NoResponse("I/O error on GET request"), RESPONSE_RECEIVED_AT);

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("UNKNOWN_AFTER_DISPATCH");
    }

    @Test
    void 응답을_받은_판은_받은_시각을_남긴다() {
        // given
        CollectionAttemptToken token = dispatchedBatch();
        final long batchId = token.batchId();

        // when
        ledger.conclude(token, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT);

        // then
        assertThat(responseReceivedAtOf(batchId)).isEqualTo(RESPONSE_RECEIVED_AT);
    }

    @Test
    void 상류가_오류로_답한_판은_실패_사유를_같이_남긴다() {
        // given
        CollectionAttemptToken token = dispatchedBatch();
        final long batchId = token.batchId();

        // when
        ledger.conclude(token, new GbisSystemError(QUERY_TIME, "시스템 오류가 발생하였습니다."), RESPONSE_RECEIVED_AT);

        // then
        assertThat(failureCodeOf(batchId)).isEqualTo("UPSTREAM_ERROR");
    }

    @Test
    void 운행_차량이_없다는_응답도_상류가_준_행_수_0을_남긴다() {
        // given
        CollectionAttemptToken token = dispatchedBatch();
        final long batchId = token.batchId();

        // when
        ledger.conclude(token, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT);

        // then
        assertThat(providerRowsOf(batchId)).isZero();
    }

    /**
     * 자리와 판이 한 트랜잭션에서 커밋된다. 판이 안 열리면 자리도 같이 되돌아가야
     * "쓴 횟수는 올랐는데 그 자리를 쓸 판이 없다" 가 안 생긴다.
     */
    @Test
    void 판을_못_열면_쓴_횟수도_안_오른다() {
        // given 없는 노선 판본이라 판을 열다가 외래 키에 걸린다
        CollectionPlan missingRoutePlan =
            new CollectionPlan(MISSING_ROUTE_VERSION_ID, SCHEDULED_AT, ATTEMPT_KEY);

        // when
        assertThatThrownBy(() -> ledger.reserve(missingRoutePlan, RESERVED_AT))
            .isInstanceOf(DataIntegrityViolationException.class);

        // then
        assertThat(quotaRowCount()).isZero();
    }

    @Test
    void 보내기_전에_한국_자정이_지나면_다음_날_자리를_쓴다() {
        // given
        CollectionAttemptToken token = ledger.reserve(plan(), KOREA_8_28_LATE_NIGHT).token();

        // when
        ledger.markDispatching(token, KOREA_8_28_LATE_NIGHT, KOREA_8_29_MIDNIGHT);

        // then
        assertThat(reservedCallsOn(KOREA_8_29)).isEqualTo(1);
    }

    @Test
    void 자정이_지났는데_다음_날_한도가_없으면_보내지_않는다() {
        // given
        CollectionAttemptToken token = ledger.reserve(plan(), KOREA_8_28_LATE_NIGHT).token();
        useUpQuotaOn(KOREA_8_29);

        // when
        final boolean actual = ledger.markDispatching(token, KOREA_8_28_LATE_NIGHT, KOREA_8_29_MIDNIGHT);

        // then
        assertThat(actual).isFalse();
    }

    @Test
    void 자정이_지나_자리를_못_잡은_판은_ABANDONED_BEFORE_SEND로_닫힌다() {
        // given
        CollectionAttemptToken token = ledger.reserve(plan(), KOREA_8_28_LATE_NIGHT).token();
        final long batchId = token.batchId();
        useUpQuotaOn(KOREA_8_29);

        // when
        ledger.markDispatching(token, KOREA_8_28_LATE_NIGHT, KOREA_8_29_MIDNIGHT);

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("ABANDONED_BEFORE_SEND");
    }

    @Test
    void 계산_입력을_확정한_배치는_같은_계획으로_다시_열_수_없다() {
        // given
        confirmSuccessfulInput();

        // when & then
        assertThatThrownBy(() -> ledger.reserve(plan(), RESERVED_AT))
            .isInstanceOf(InputAlreadyConfirmedException.class);
    }

    @Test
    void 계산_입력이_확정되어_재수집이_거절되면_호출_횟수도_늘지_않는다() {
        // given
        confirmSuccessfulInput();

        // when
        assertThatThrownBy(() -> ledger.reserve(plan(), RESERVED_AT))
            .isInstanceOf(InputAlreadyConfirmedException.class);

        // then 첫 예약 하나에서 안 늘었다
        assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(1);
    }

    @Test
    void 같은_계획을_재시도하면_수집_묶음은_한_행으로_남는다() {
        // given
        ledger.reserve(plan(), RESERVED_AT);

        // when
        ledger.reserve(plan(), RESERVED_AT);

        // then
        assertThat(batchCountOf(ATTEMPT_KEY)).isEqualTo(1);
    }

    @Test
    void 같은_계획을_재시도하면_한도를_두_번_쓴다() {
        // given
        ledger.reserve(plan(), RESERVED_AT);

        // when
        ledger.reserve(plan(), RESERVED_AT);

        // then
        assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(2);
    }

    @Test
    void 같은_계획을_재시도하면_시도_횟수가_2가_된다() {
        // given
        final long batchId = ledger.reserve(plan(), RESERVED_AT).batchId();

        // when
        ledger.reserve(plan(), RESERVED_AT);

        // then
        assertThat(attemptNumberOf(batchId)).isEqualTo(2);
    }

    @Test
    void 재시도로_다시_연_판은_지난_시도의_보낸_시각을_들고_있지_않는다() {
        // given
        CollectionAttemptToken token = dispatchedBatch();
        final long batchId = token.batchId();

        // when
        ledger.reserve(plan(), RESERVED_AT);

        // then
        assertThat(requestedAtOf(batchId)).isNull();
    }

    @Test
    void 이전_시도의_응답은_새_시도의_결과를_바꾸지_못한다() {
        // given
        CollectionAttemptToken previous = dispatchedBatch();
        CollectionAttemptToken current = ledger.reserve(plan(), RESERVED_AT).token();
        ledger.markDispatching(current, RESERVED_AT, REQUESTED_AT.plusSeconds(1));

        // when & then
        assertThatThrownBy(() -> ledger.conclude(previous, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT))
            .isInstanceOf(StaleCollectionAttemptException.class);
        assertThat(outcomeOf(current.batchId())).isEqualTo("DISPATCHING");
        assertThat(attemptNumberOf(current.batchId())).isEqualTo(current.attemptNumber());
        assertThat(requestedAtOf(current.batchId())).isEqualTo(REQUESTED_AT.plusSeconds(1));
        assertThat(responseReceivedAtOf(current.batchId())).isNull();
    }

    @Test
    void 이전_시도의_중단_요청은_새_예약을_바꾸지_못한다() {
        // given
        CollectionAttemptToken previous = ledger.reserve(plan(), RESERVED_AT).token();
        CollectionAttemptToken current = ledger.reserve(plan(), RESERVED_AT).token();

        // when & then
        assertThatThrownBy(() -> ledger.abandonBeforeSend(previous))
            .isInstanceOf(StaleCollectionAttemptException.class);
        assertThat(outcomeOf(current.batchId())).isEqualTo("RESERVED");
        assertThat(attemptNumberOf(current.batchId())).isEqualTo(current.attemptNumber());
    }

    @Test
    void 이전_시도의_전송_요청은_자정이_지나도_새_날짜의_한도를_사용하지_않는다() {
        // given
        CollectionAttemptToken previous = ledger.reserve(plan(), KOREA_8_28_LATE_NIGHT).token();
        CollectionAttemptToken current = ledger.reserve(plan(), KOREA_8_28_LATE_NIGHT).token();

        // when & then
        assertThatThrownBy(() -> ledger.markDispatching(previous, KOREA_8_28_LATE_NIGHT, KOREA_8_29_MIDNIGHT))
            .isInstanceOf(StaleCollectionAttemptException.class);
        assertThat(quotaRowCount()).isEqualTo(1);
        assertThat(outcomeOf(current.batchId())).isEqualTo("RESERVED");
        assertThat(requestedAtOf(current.batchId())).isNull();
    }

    @Test
    void 자정을_넘긴_전송_기록을_다시_요청해도_호출_횟수는_늘지_않는다() {
        // given
        CollectionAttemptToken token = ledger.reserve(plan(), KOREA_8_28_LATE_NIGHT).token();
        ledger.markDispatching(token, KOREA_8_28_LATE_NIGHT, KOREA_8_29_MIDNIGHT);

        // when
        ledger.markDispatching(token, KOREA_8_28_LATE_NIGHT, KOREA_8_29_MIDNIGHT);

        // then
        assertThat(reservedCallsOn(KOREA_8_29)).isEqualTo(1);
        assertThat(requestedAtOf(token.batchId())).isEqualTo(KOREA_8_29_MIDNIGHT);
    }

    @Test
    void 같은_빈_응답을_다시_전달해도_최초_결과가_유지된다() {
        // given
        CollectionAttemptToken token = dispatchedBatch();
        ledger.conclude(token, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT);

        // when
        ledger.conclude(token, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT);

        // then
        assertThat(outcomeOf(token.batchId())).isEqualTo("SUCCESS_EMPTY");
        assertThat(responseReceivedAtOf(token.batchId())).isEqualTo(RESPONSE_RECEIVED_AT);
        assertThat(providerRowsOf(token.batchId())).isZero();
    }

    @Test
    void 완료한_시도에_다른_결과를_전달하면_기존_결과를_유지하고_거절한다() {
        // given
        CollectionAttemptToken token = dispatchedBatch();
        ledger.conclude(token, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT);

        // when & then
        assertThatThrownBy(() -> ledger.conclude(token, new NoResponse("timeout"), RESPONSE_RECEIVED_AT))
            .isInstanceOf(ConflictingCollectionResultException.class);
        assertThat(outcomeOf(token.batchId())).isEqualTo("SUCCESS_EMPTY");
        assertThat(responseReceivedAtOf(token.batchId())).isEqualTo(RESPONSE_RECEIVED_AT);
    }

    @Test
    void 같은_결과라도_완료_시각이_다르면_기존_결과를_덮어쓰지_않는다() {
        // given
        CollectionAttemptToken token = dispatchedBatch();
        ledger.conclude(token, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT);

        // when & then
        assertThatThrownBy(() -> ledger.conclude(token, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT.plusSeconds(1)))
            .isInstanceOf(ConflictingCollectionResultException.class);
        assertThat(responseReceivedAtOf(token.batchId())).isEqualTo(RESPONSE_RECEIVED_AT);
    }

    @Test
    void 계산_입력을_다시_확정해도_최초_확정_시각은_유지된다() {
        // given
        CollectionAttemptToken token = confirmSuccessfulInput();

        // when
        confirmInput(token, RESPONSE_RECEIVED_AT.plusSeconds(2));

        // then
        assertThat(columnOf("input_confirmed_at", token.batchId(), OffsetDateTime.class))
            .isEqualTo(RESPONSE_RECEIVED_AT.plusSeconds(1));
    }

    @Test
    void 재수집을_시작한_뒤에는_이전_시도의_입력을_확정할_수_없다() {
        // given
        CollectionAttemptToken previous = dispatchedBatch();
        ledger.conclude(previous, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT);
        CollectionAttemptToken current = ledger.reserve(plan(), RESERVED_AT).token();

        // when & then
        assertThatThrownBy(() -> confirmInput(previous, RESPONSE_RECEIVED_AT.plusSeconds(1)))
            .isInstanceOf(StaleCollectionAttemptException.class);
        assertThat(outcomeOf(current.batchId())).isEqualTo("RESERVED");
        assertThat(columnOf("input_confirmed_at", current.batchId(), OffsetDateTime.class)).isNull();
    }

    @Test
    void 품질_처리가_실패하면_관측과_완료_상태는_롤백되고_전송_기록은_남는다() {
        // given
        insertStop();
        CollectionAttemptToken token = dispatchedBatch();
        failingQualityHook.fail = true;
        BusLocation bus = new BusLocation(
            "경기70아0001", VEHICLE_ID, 0, ROUTE_204000057, 11,
            STOP_ID, 1, 2, 71, 3, 1);

        // when & then
        assertThatThrownBy(() -> ledger.conclude(token, new Success(QUERY_TIME, List.of(bus)), RESPONSE_RECEIVED_AT))
            .isInstanceOf(InvalidDataAccessApiUsageException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("품질 처리 실패");
        assertThat(outcomeOf(token.batchId())).isEqualTo("DISPATCHING");
        assertThat(requestedAtOf(token.batchId())).isEqualTo(REQUESTED_AT);
        assertThat(responseReceivedAtOf(token.batchId())).isNull();
        assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?")
            .param(token.batchId()).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM trip_quality_rebuild WHERE route_version_id = ?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
    }

    private CollectionPlan plan() {
        return new CollectionPlan(routeVersionId, SCHEDULED_AT, ATTEMPT_KEY);
    }

    private CollectionAttemptToken dispatchedBatch() {
        CollectionAttemptToken token = ledger.reserve(plan(), RESERVED_AT).token();
        ledger.markDispatching(token, RESERVED_AT, REQUESTED_AT);
        return token;
    }

    private void insertStop() {
        jdbcClient.sql("""
                INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
                VALUES (?, 1, ?, '범계역', 'UP', true)
                """)
            .params(routeVersionId, STOP_ID)
            .update();
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

    private int quotaRowCount() {
        return jdbcClient.sql("SELECT count(*) FROM daily_call_quota").query(Integer.class).single();
    }

    private void useUpQuotaOn(
        LocalDate kstDate
    ) {
        jdbcClient.sql("""
                INSERT INTO daily_call_quota (provider, api_service, kst_date, reserved_calls, daily_limit)
                VALUES (?, ?, ?, ?, ?)
                """)
            .params(
                CallQuota.BUS_LOCATION.provider(), CallQuota.BUS_LOCATION.apiService(),
                kstDate, ALREADY_USED_UP, ALREADY_USED_UP)
            .update();
    }

    private CollectionAttemptToken confirmSuccessfulInput() {
        CollectionAttemptToken token = dispatchedBatch();
        ledger.conclude(token, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT);
        confirmInput(token, RESPONSE_RECEIVED_AT.plusSeconds(1));
        return token;
    }

    private void confirmInput(
        CollectionAttemptToken token,
        OffsetDateTime confirmedAt
    ) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            collectionInputs.lockForForecast(token.batchId());
            collectionInputs.confirmInput(token.batchId(), token.attemptNumber(), confirmedAt.toInstant());
        });
    }

    private void useUpTheQuota() {
        jdbcClient.sql("""
                INSERT INTO daily_call_quota (provider, api_service, kst_date, reserved_calls, daily_limit)
                VALUES (?, ?, ?, ?, ?)
                """)
            .params(
                CallQuota.BUS_LOCATION.provider(), CallQuota.BUS_LOCATION.apiService(),
                KOREA_8_19, ALREADY_USED_UP, ALREADY_USED_UP)
            .update();
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
            .params(routeId, CONTENT_DIGEST, SCHEDULED_AT)
            .query(Long.class)
            .single();
    }

    private String outcomeOf(
        final long batchId
    ) {
        return columnOf("outcome", batchId, String.class);
    }

    private String failureCodeOf(
        final long batchId
    ) {
        return columnOf("failure_code", batchId, String.class);
    }

    private OffsetDateTime requestedAtOf(
        final long batchId
    ) {
        return columnOf("requested_at", batchId, OffsetDateTime.class);
    }

    private OffsetDateTime responseReceivedAtOf(
        final long batchId
    ) {
        return columnOf("response_received_at", batchId, OffsetDateTime.class);
    }

    private Integer providerRowsOf(
        final long batchId
    ) {
        return columnOf("provider_rows", batchId, Integer.class);
    }

    private Integer attemptNumberOf(
        final long batchId
    ) {
        return columnOf("attempt_number", batchId, Integer.class);
    }

    private <T> T columnOf(
        final String column,
        final long batchId,
        Class<T> type
    ) {
        return jdbcClient.sql("SELECT %s FROM observation_batch WHERE id = ?".formatted(column))
            .param(batchId)
            .query(type)
            .optional()
            .orElse(null);
    }

    private int batchCountOf(
        final String attemptKey
    ) {
        return jdbcClient.sql("SELECT count(*) FROM observation_batch WHERE attempt_key = ?")
            .param(attemptKey)
            .query(Integer.class)
            .single();
    }

    private int reservedCallsOn(
        LocalDate kstDate
    ) {
        return jdbcClient.sql("""
                SELECT reserved_calls FROM daily_call_quota
                WHERE provider = ? AND api_service = ? AND kst_date = ?
                """)
            .params(CallQuota.BUS_LOCATION.provider(), CallQuota.BUS_LOCATION.apiService(), kstDate)
            .query(Integer.class)
            .single();
    }
}
