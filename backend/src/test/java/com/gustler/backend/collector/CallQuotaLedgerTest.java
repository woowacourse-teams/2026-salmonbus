package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예약은 제 트랜잭션에서 커밋되므로 테스트에 @Transactional 을 붙이면 정리가 안 된다.
 * 매번 장부를 비우고 시작한다.
 */
@IntegrationTest
class CallQuotaLedgerTest {

    private static final OffsetDateTime KOREA_8_28_LATE_NIGHT = OffsetDateTime.parse("2026-08-28T14:59:59Z");
    private static final OffsetDateTime KOREA_8_29_MIDNIGHT = OffsetDateTime.parse("2026-08-28T15:00:00Z");
    private static final LocalDate KOREA_8_28 = LocalDate.of(2026, 8, 28);
    private static final LocalDate KOREA_8_29 = LocalDate.of(2026, 8, 29);

    private static final int ONE_SEAT_LEFT = 1;
    private static final int NO_SEAT_LEFT = 0;
    private static final int ALREADY_USED_UP = 3;

    @Autowired
    private CallQuotaLedger ledger;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void 장부를_비운다() {
        jdbcClient.sql("DELETE FROM daily_call_quota").update();
    }

    @Test
    void 자리가_남은_장부는_예약을_받아준다() {
        // when
        final boolean actual = ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);

        // then
        assertThat(actual).isTrue();
    }

    @Test
    void 예약할_때마다_쓴_횟수가_하나씩_오른다() {
        // when
        ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);
        ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);
        ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);

        // then
        assertThat(reservedCallsOn(KOREA_8_28, CallQuota.BUS_LOCATION)).isEqualTo(3);
    }

    @Test
    void 한도를_다_쓴_장부는_예약을_거절한다() {
        // given
        insertQuota(CallQuota.BUS_LOCATION, KOREA_8_28, ALREADY_USED_UP, ALREADY_USED_UP);

        // when
        final boolean actual = ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);

        // then
        assertThat(actual).isFalse();
    }

    @Test
    void 거절된_예약은_쓴_횟수를_올리지_않는다() {
        // given
        insertQuota(CallQuota.BUS_LOCATION, KOREA_8_28, ALREADY_USED_UP, ALREADY_USED_UP);

        // when
        ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);

        // then
        assertThat(reservedCallsOn(KOREA_8_28, CallQuota.BUS_LOCATION)).isEqualTo(ALREADY_USED_UP);
    }

    @Test
    void 한국_날짜가_바뀌면_쓴_횟수를_다시_0부터_센다() {
        // given
        ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);
        ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);

        // when
        ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_29_MIDNIGHT);

        // then
        assertThat(reservedCallsOn(KOREA_8_29, CallQuota.BUS_LOCATION)).isEqualTo(1);
    }

    @Test
    void 세계_표준시로_같은_날이어도_한국_날짜가_다르면_장부가_갈린다() {
        // when
        ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);
        ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_29_MIDNIGHT);

        // then
        assertThat(ledgerDates()).containsExactly(KOREA_8_28, KOREA_8_29);
    }

    @Test
    void 위치정보_호출과_노선정보_호출은_장부가_갈린다() {
        // given
        insertQuota(CallQuota.BUS_LOCATION, KOREA_8_28, ALREADY_USED_UP, ALREADY_USED_UP);

        // when
        final boolean actual = ledger.reserve(CallQuota.BUS_ROUTE, KOREA_8_28_LATE_NIGHT);

        // then
        assertThat(actual).isTrue();
    }

    /**
     * 트랜잭션 경계를 부르는 쪽이 갖는다. 여기서 제 트랜잭션을 열면 자리는 잡혔는데
     * 그 자리를 쓸 판이 안 열리는 틈이 생긴다. 되돌아가도 남는 것은 ObservationBatchLedger 가 보장한다.
     */
    @Test
    void 예약은_부른_쪽_트랜잭션에_합류한다() {
        // when
        transactionTemplate.executeWithoutResult(status -> {
            ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);
            status.setRollbackOnly();
        });

        // then
        assertThat(quotaRowCountOn(KOREA_8_28, CallQuota.BUS_LOCATION)).isZero();
    }

    @Test
    void 자리를_잡은_날과_보내는_날이_같으면_이미_잡은_자리를_쓴다() {
        // given
        ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);

        // when
        ledger.holdsSeatAt(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT, KOREA_8_28_LATE_NIGHT);

        // then
        assertThat(reservedCallsOn(KOREA_8_28, CallQuota.BUS_LOCATION)).isEqualTo(1);
    }

    @Test
    void 자리를_잡고_보내기_전에_한국_자정이_지나면_다음_날_자리를_새로_잡는다() {
        // given 한국 시각 23:59:59 에 잡고 00:00:00 에 보낸다
        ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);

        // when
        ledger.holdsSeatAt(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT, KOREA_8_29_MIDNIGHT);

        // then
        assertThat(reservedCallsOn(KOREA_8_29, CallQuota.BUS_LOCATION)).isEqualTo(1);
    }

    @Test
    void 자정이_지났는데_다음_날_한도가_없으면_자리를_못_잡는다() {
        // given
        insertQuota(CallQuota.BUS_LOCATION, KOREA_8_29, ALREADY_USED_UP, ALREADY_USED_UP);

        // when
        final boolean actual =
            ledger.holdsSeatAt(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT, KOREA_8_29_MIDNIGHT);

        // then
        assertThat(actual).isFalse();
    }

    @Test
    void 한_자리만_남은_장부에_동시에_두_번_예약하면_하나만_받아준다() throws Exception {
        // given
        insertQuota(CallQuota.BUS_LOCATION, KOREA_8_28, NO_SEAT_LEFT, ONE_SEAT_LEFT);

        // when
        final List<Boolean> actual = reserveAtTheSameMoment();

        // then
        assertThat(actual).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void 한_자리만_남은_장부에_동시에_두_번_예약해도_쓴_횟수는_1이다() throws Exception {
        // given
        insertQuota(CallQuota.BUS_LOCATION, KOREA_8_28, NO_SEAT_LEFT, ONE_SEAT_LEFT);

        // when
        reserveAtTheSameMoment();

        // then
        assertThat(reservedCallsOn(KOREA_8_28, CallQuota.BUS_LOCATION)).isEqualTo(ONE_SEAT_LEFT);
    }

    private List<Boolean> reserveAtTheSameMoment() throws Exception {
        final CountDownLatch startTogether = new CountDownLatch(1);

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = threads.submit(() -> reserveAfter(startTogether));
            Future<Boolean> second = threads.submit(() -> reserveAfter(startTogether));

            startTogether.countDown();
            return List.of(first.get(), second.get());
        }
    }

    private boolean reserveAfter(
        CountDownLatch startTogether
    ) throws InterruptedException {
        startTogether.await();
        return ledger.reserve(CallQuota.BUS_LOCATION, KOREA_8_28_LATE_NIGHT);
    }

    private void insertQuota(
        CallQuota quota,
        LocalDate kstDate,
        final int reservedCalls,
        final int dailyLimit
    ) {
        jdbcClient.sql("""
                INSERT INTO daily_call_quota (provider, api_service, kst_date, reserved_calls, daily_limit)
                VALUES (?, ?, ?, ?, ?)
                """)
            .params(quota.provider(), quota.apiService(), kstDate, reservedCalls, dailyLimit)
            .update();
    }

    private int quotaRowCountOn(
        LocalDate kstDate,
        CallQuota quota
    ) {
        return jdbcClient.sql("""
                SELECT count(*) FROM daily_call_quota
                WHERE provider = ? AND api_service = ? AND kst_date = ?
                """)
            .params(quota.provider(), quota.apiService(), kstDate)
            .query(Integer.class)
            .single();
    }

    private int reservedCallsOn(
        LocalDate kstDate,
        CallQuota quota
    ) {
        return jdbcClient.sql("""
                SELECT reserved_calls FROM daily_call_quota
                WHERE provider = ? AND api_service = ? AND kst_date = ?
                """)
            .params(quota.provider(), quota.apiService(), kstDate)
            .query(Integer.class)
            .single();
    }

    private List<LocalDate> ledgerDates() {
        return jdbcClient.sql("SELECT kst_date FROM daily_call_quota ORDER BY kst_date")
            .query(LocalDate.class)
            .list();
    }
}
