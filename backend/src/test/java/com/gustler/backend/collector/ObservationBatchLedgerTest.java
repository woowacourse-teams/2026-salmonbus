package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.collector.GbisLocationResult.GbisSystemError;
import com.gustler.backend.collector.GbisLocationResult.NoResponse;
import com.gustler.backend.collector.GbisLocationResult.NoVehicles;
import com.gustler.backend.support.IntegrationTest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 예약이 제 트랜잭션에서 커밋되므로 @Transactional 을 붙이면 장부가 정리되지 않는다.
 * 매번 비우고 시작한다.
 */
@IntegrationTest
class ObservationBatchLedgerTest {

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String ATTEMPT_KEY = "204000057-2026-08-19T11:14";
    private static final String QUERY_TIME = "2026-08-19 11:14:04.9";

    private static final OffsetDateTime SCHEDULED_AT = OffsetDateTime.parse("2026-08-19T11:14:00+09:00");
    private static final OffsetDateTime RESERVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.700+09:00");
    private static final OffsetDateTime REQUESTED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.800+09:00");
    private static final OffsetDateTime RESPONSE_RECEIVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final LocalDate KOREA_8_19 = LocalDate.of(2026, 8, 19);

    private static final int ALREADY_USED_UP = 3;

    @Autowired
    private ObservationBatchLedger ledger;

    @Autowired
    private JdbcClient jdbcClient;

    private long routeVersionId;

    @BeforeEach
    void 노선과_판본을_먼저_저장한다() {
        routeVersionId = insertRouteVersion(insertRoute());
    }

    @AfterEach
    void 쌓인_것을_비운다() {
        jdbcClient.sql("""
                TRUNCATE vehicle_observation, observation_batch, route_stop, route_version, route,
                    daily_call_quota RESTART IDENTITY CASCADE
                """)
            .update();
    }

    @Test
    void 자리를_잡은_판은_RESERVED로_남는다() {
        // when
        final long batchId = ledger.reserve(attempt(), RESERVED_AT).batchId();

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("RESERVED");
    }

    @Test
    void 자리를_잡은_판은_한도를_한_번_쓴다() {
        // when
        ledger.reserve(attempt(), RESERVED_AT);

        // then
        assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(1);
    }

    @Test
    void 자리를_못_잡은_판은_NOT_RESERVED로_남는다() {
        // given
        useUpTheQuota();

        // when
        final long batchId = ledger.reserve(attempt(), RESERVED_AT).batchId();

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("NOT_RESERVED");
    }

    @Test
    void 자리를_못_잡은_판의_실패_사유는_LOCAL_QUOTA_EXHAUSTED다() {
        // given
        useUpTheQuota();

        // when
        final long batchId = ledger.reserve(attempt(), RESERVED_AT).batchId();

        // then
        assertThat(failureCodeOf(batchId)).isEqualTo("LOCAL_QUOTA_EXHAUSTED");
    }

    @Test
    void 자리를_못_잡은_판은_보낸_시각이_비어_있다() {
        // given
        useUpTheQuota();

        // when
        final long batchId = ledger.reserve(attempt(), RESERVED_AT).batchId();

        // then
        assertThat(requestedAtOf(batchId)).isNull();
    }

    @Test
    void 보내기_직전의_판은_DISPATCHING으로_바뀐다() {
        // given
        final long batchId = ledger.reserve(attempt(), RESERVED_AT).batchId();

        // when
        ledger.markDispatching(batchId, REQUESTED_AT);

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("DISPATCHING");
    }

    @Test
    void 보내기_직전의_판은_보낸_시각을_남긴다() {
        // given
        final long batchId = ledger.reserve(attempt(), RESERVED_AT).batchId();

        // when
        ledger.markDispatching(batchId, REQUESTED_AT);

        // then
        assertThat(requestedAtOf(batchId)).isEqualTo(REQUESTED_AT);
    }

    @Test
    void 보내지_않고_그만둔_판은_ABANDONED_BEFORE_SEND로_남는다() {
        // given
        final long batchId = ledger.reserve(attempt(), RESERVED_AT).batchId();

        // when
        ledger.abandonBeforeSend(batchId);

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("ABANDONED_BEFORE_SEND");
    }

    @Test
    void 보냈는데_응답이_오지_않은_판은_UNKNOWN_AFTER_DISPATCH로_남는다() {
        // given
        final long batchId = dispatchedBatch();

        // when
        ledger.conclude(batchId, new NoResponse("I/O error on GET request"), RESPONSE_RECEIVED_AT);

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("UNKNOWN_AFTER_DISPATCH");
    }

    @Test
    void 응답을_받은_판은_받은_시각을_남긴다() {
        // given
        final long batchId = dispatchedBatch();

        // when
        ledger.conclude(batchId, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT);

        // then
        assertThat(responseReceivedAtOf(batchId)).isEqualTo(RESPONSE_RECEIVED_AT);
    }

    @Test
    void 상류가_오류로_답한_판은_실패_사유를_같이_남긴다() {
        // given
        final long batchId = dispatchedBatch();

        // when
        ledger.conclude(batchId, new GbisSystemError(QUERY_TIME, "시스템 오류가 발생하였습니다."), RESPONSE_RECEIVED_AT);

        // then
        assertThat(failureCodeOf(batchId)).isEqualTo("UPSTREAM_ERROR");
    }

    @Test
    void 운행_차량이_없다는_응답도_상류가_준_행_수_0을_남긴다() {
        // given
        final long batchId = dispatchedBatch();

        // when
        ledger.conclude(batchId, new NoVehicles(QUERY_TIME), RESPONSE_RECEIVED_AT);

        // then
        assertThat(providerRowsOf(batchId)).isZero();
    }

    @Test
    void 같은_계획을_재시도하면_수집_묶음은_한_행으로_남는다() {
        // given
        ledger.reserve(attempt(), RESERVED_AT);

        // when
        ledger.reserve(attempt(), RESERVED_AT);

        // then
        assertThat(batchCountOf(ATTEMPT_KEY)).isEqualTo(1);
    }

    @Test
    void 같은_계획을_재시도하면_한도를_두_번_쓴다() {
        // given
        ledger.reserve(attempt(), RESERVED_AT);

        // when
        ledger.reserve(attempt(), RESERVED_AT);

        // then
        assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(2);
    }

    @Test
    void 같은_계획을_재시도하면_시도_횟수가_2가_된다() {
        // given
        final long batchId = ledger.reserve(attempt(), RESERVED_AT).batchId();

        // when
        ledger.reserve(attempt(), RESERVED_AT);

        // then
        assertThat(attemptNumberOf(batchId)).isEqualTo(2);
    }

    @Test
    void 재시도로_다시_연_판은_지난_시도의_보낸_시각을_들고_있지_않는다() {
        // given
        final long batchId = dispatchedBatch();

        // when
        ledger.reserve(attempt(), RESERVED_AT);

        // then
        assertThat(requestedAtOf(batchId)).isNull();
    }

    private ObservationAttempt attempt() {
        return new ObservationAttempt(routeVersionId, SCHEDULED_AT, ATTEMPT_KEY);
    }

    private long dispatchedBatch() {
        final long batchId = ledger.reserve(attempt(), RESERVED_AT).batchId();
        ledger.markDispatching(batchId, REQUESTED_AT);
        return batchId;
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
