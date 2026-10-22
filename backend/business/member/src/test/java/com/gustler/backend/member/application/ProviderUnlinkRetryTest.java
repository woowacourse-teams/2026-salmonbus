package com.gustler.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.PendingUnlink;
import com.gustler.backend.member.api.ProviderUnlink;
import com.gustler.backend.member.api.ProviderUnlinks;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.member.api.WithdrawMember;
import com.gustler.backend.member.domain.ProviderUnlinkPolicy;
import com.gustler.backend.member.support.AdjustableClock;
import com.gustler.backend.member.support.MemberIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@MemberIntegrationTest
class ProviderUnlinkRetryTest {
    private static final int CONCURRENT_REQUESTS = 8;
    private static final int CLAIM_LIMIT = 10;
    private static final Duration ONE_MICRO = Duration.ofNanos(1_000);
    private static final MemberIdentity IDENTITY = new MemberIdentity(LoginProvider.APPLE, "apple-withdrawn");

    @Autowired
    private ProviderUnlinks providerUnlinks;

    @Autowired
    private WithdrawMember withdrawMember;

    @Autowired
    private SignInWithSocialAccount signIn;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void 회원과_연결_해제_요청을_비우고_시계를_되돌린다() {
        jdbcClient.sql("DELETE FROM member").update();
        jdbcClient.sql("DELETE FROM provider_unlink").update();
        clock.reset();
    }

    @Test
    void 탈퇴하며_임대한_요청은_임대가_끝나기_전에는_나오지_않고_끝나면_나온다() {
        // given
        PendingUnlink pending = withdrawPending();

        // when
        List<PendingUnlink> leased = providerUnlinks.claimDue(CLAIM_LIMIT);
        clock.advance(ProviderUnlinkPolicy.LEASE.minus(ONE_MICRO));
        List<PendingUnlink> justBefore = providerUnlinks.claimDue(CLAIM_LIMIT);
        clock.advance(ONE_MICRO);
        List<PendingUnlink> expired = providerUnlinks.claimDue(CLAIM_LIMIT);

        // then
        assertSoftly(softly -> {
            softly.assertThat(leased).isEmpty();
            softly.assertThat(justBefore).isEmpty();
            softly.assertThat(expired).containsExactly(pending);
        });
    }

    @Test
    void 집은_요청은_임대_시간_동안_다시_나오지_않는다() {
        // given
        final long id = insertUnlink("PENDING", 0, Duration.ZERO);

        // when
        List<PendingUnlink> first = providerUnlinks.claimDue(CLAIM_LIMIT);
        List<PendingUnlink> second = providerUnlinks.claimDue(CLAIM_LIMIT);

        // then
        assertSoftly(softly -> {
            softly.assertThat(ids(first)).containsExactly(id);
            softly.assertThat(second).isEmpty();
            softly.assertThat(nextAttemptAt(id).toInstant())
                .isEqualTo(clock.instant().plus(ProviderUnlinkPolicy.LEASE));
        });
    }

    @Test
    void 개수_상한까지만_오래_기다린_요청부터_집는다() {
        // given
        List<Long> oldestFirst = IntStream.range(0, 5)
            .mapToObj(index -> insertUnlink("PENDING", 0, Duration.ofMinutes(index - 10L)))
            .toList();

        // when
        List<PendingUnlink> actual = providerUnlinks.claimDue(3);

        // then
        assertThat(ids(actual)).containsExactlyInAnyOrderElementsOf(oldestFirst.subList(0, 3));
    }

    @Test
    void 포기한_요청은_집지_않는다() {
        // given
        insertUnlink("GAVE_UP", ProviderUnlinkPolicy.MAX_ATTEMPTS, Duration.ofDays(-1));

        // when
        List<PendingUnlink> actual = providerUnlinks.claimDue(CLAIM_LIMIT);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 개수가_1보다_작으면_거절한다() {
        // when & then
        assertThatThrownBy(() -> providerUnlinks.claimDue(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 여러_곳에서_동시에_집어도_같은_요청을_두_번_돌려주지_않는다() throws Exception {
        // given
        List<Long> inserted = IntStream.range(0, 40)
            .mapToObj(index -> insertUnlink("PENDING", 0, Duration.ofSeconds(-index)))
            .toList();

        // when
        List<List<PendingUnlink>> actual = runConcurrently(index -> providerUnlinks.claimDue(CLAIM_LIMIT));

        // then
        List<Long> claimed = actual.stream().flatMap(Collection::stream).map(PendingUnlink::id).toList();
        assertSoftly(softly -> {
            softly.assertThat(claimed).doesNotHaveDuplicates();
            softly.assertThat(claimed).containsExactlyInAnyOrderElementsOf(inserted);
        });
    }

    @Test
    void 성공하면_요청을_지운다() {
        // given
        PendingUnlink pending = withdrawPending();

        // when
        providerUnlinks.succeeded(pending.id());
        providerUnlinks.succeeded(pending.id());

        // then
        assertThat(unlinkCount()).isZero();
    }

    @Test
    void 실패하면_횟수를_올리고_실패할_때마다_두_배씩_늦춰_다시_시도한다() {
        // given
        PendingUnlink pending = withdrawPending();

        // when
        providerUnlinks.failed(pending.id(), "kakao unlink timeout");
        Row first = row(pending.id());
        OffsetDateTime firstFailedAt = now();
        clock.advance(Duration.ofMinutes(1));
        List<PendingUnlink> retried = providerUnlinks.claimDue(CLAIM_LIMIT);
        providerUnlinks.failed(pending.id(), "kakao unlink 500");
        Row second = row(pending.id());

        // then
        assertSoftly(softly -> {
            softly.assertThat(first.status()).isEqualTo("PENDING");
            softly.assertThat(first.attempts()).isEqualTo(1);
            softly.assertThat(first.nextAttemptAt().toInstant()).isEqualTo(firstFailedAt.plusMinutes(1).toInstant());
            softly.assertThat(first.lastError()).isEqualTo("kakao unlink timeout");
            softly.assertThat(retried).containsExactly(new PendingUnlink(pending.id(), IDENTITY, 1));
            softly.assertThat(second.attempts()).isEqualTo(2);
            softly.assertThat(second.nextAttemptAt().toInstant()).isEqualTo(now().plusMinutes(2).toInstant());
            softly.assertThat(second.lastError()).isEqualTo("kakao unlink 500");
        });
    }

    @Test
    void 실패_원인이_길면_200자로_자르고_없으면_고정_문구를_남긴다() {
        // given
        final long longReasonId = insertUnlink("PENDING", 0, Duration.ZERO);
        final long nullReasonId = insertUnlink("PENDING", 0, Duration.ZERO);

        // when
        providerUnlinks.failed(longReasonId, "가".repeat(300));
        providerUnlinks.failed(nullReasonId, null);

        // then
        assertSoftly(softly -> {
            softly.assertThat(row(longReasonId).lastError()).isEqualTo("가".repeat(200));
            softly.assertThat(row(nullReasonId).lastError()).isEqualTo(ProviderUnlinkPolicy.UNKNOWN_ERROR);
        });
    }

    @Test
    void 열_번째_실패에서_포기하고_그_뒤로는_집지도_실패를_세지도_않는다() {
        // given
        PendingUnlink pending = withdrawPending();
        for (int failure = 1; failure < ProviderUnlinkPolicy.MAX_ATTEMPTS; failure++) {
            providerUnlinks.failed(pending.id(), "failure " + failure);
        }
        Row beforeLast = row(pending.id());

        // when
        providerUnlinks.failed(pending.id(), "last failure");
        clock.advance(Duration.ofDays(7));
        List<PendingUnlink> claimed = providerUnlinks.claimDue(CLAIM_LIMIT);
        providerUnlinks.failed(pending.id(), "after giving up");

        // then
        Row actual = row(pending.id());
        assertSoftly(softly -> {
            softly.assertThat(beforeLast.status()).isEqualTo("PENDING");
            softly.assertThat(beforeLast.attempts()).isEqualTo(ProviderUnlinkPolicy.MAX_ATTEMPTS - 1);
            softly.assertThat(actual.status()).isEqualTo("GAVE_UP");
            softly.assertThat(actual.attempts()).isEqualTo(ProviderUnlinkPolicy.MAX_ATTEMPTS);
            softly.assertThat(actual.lastError()).isEqualTo("last failure");
            softly.assertThat(claimed).isEmpty();
        });
    }

    @Test
    void 없는_요청의_실패와_성공은_아무_일도_하지_않는다() {
        // given
        final long id = insertUnlink("PENDING", 0, Duration.ZERO);

        // when
        providerUnlinks.failed(id + 1, "unknown");
        providerUnlinks.succeeded(id + 1);

        // then
        Row actual = row(id);
        assertSoftly(softly -> {
            softly.assertThat(unlinkCount()).isEqualTo(1);
            softly.assertThat(actual.attempts()).isZero();
            softly.assertThat(actual.lastError()).isNull();
        });
    }

    @Test
    void 집은_요청에는_지금까지의_실패_횟수와_제공자_계정이_담긴다() {
        // given
        final long id = insertUnlink("PENDING", 3, Duration.ofMinutes(-1));

        // when
        List<PendingUnlink> actual = providerUnlinks.claimDue(CLAIM_LIMIT);

        // then
        assertThat(actual).containsExactly(new PendingUnlink(id, IDENTITY, 3));
    }

    @Test
    void 포기한_지_30일이_지난_요청만_지운다() {
        // given
        final long old = insertUnlink("GAVE_UP", 10, Duration.ZERO);
        final long oldPending = insertUnlink("PENDING", 1, Duration.ZERO);
        clock.advance(Duration.ofMinutes(1));
        final long insideRetention = insertUnlink("GAVE_UP", 10, Duration.ZERO);
        clock.advance(Duration.ofDays(30).minus(Duration.ofMinutes(1)).plus(ONE_MICRO));
        final long recent = insertUnlink("GAVE_UP", 10, Duration.ZERO);

        // when
        final int deleted = providerUnlinks.purgeGaveUp();

        // then
        List<Long> remaining = jdbcClient.sql("SELECT id FROM provider_unlink ORDER BY id").query(Long.class).list();
        assertSoftly(softly -> {
            softly.assertThat(deleted).as("30일하고 1µs 지난 포기 요청 하나").isEqualTo(1);
            softly.assertThat(remaining).doesNotContain(old);
            softly.assertThat(remaining).as("30일이 안 된 포기 요청").contains(insideRetention, recent);
            softly.assertThat(remaining).as("오래됐어도 대기 중인 요청").contains(oldPending);
        });
    }

    @Test
    void 같은_제공자_계정으로_다시_가입하면_남은_연결_해제_요청을_지운다() {
        // given
        withdrawPending();
        insertUnlink("GAVE_UP", 10, Duration.ZERO);
        final long otherSubject = insertUnlink("GAVE_UP", 10, Duration.ZERO, "KAKAO", IDENTITY.subject());

        // when
        signIn.signIn(new SocialIdentity(IDENTITY.provider(), IDENTITY.subject()));

        // then
        List<Long> remaining = jdbcClient.sql("SELECT id FROM provider_unlink").query(Long.class).list();
        assertThat(remaining).as("제공자가 다르면 다른 계정이다").containsExactly(otherSubject);
    }

    private PendingUnlink withdrawPending() {
        SocialIdentity identity = new SocialIdentity(IDENTITY.provider(), IDENTITY.subject());
        return withdrawMember.withdraw(signIn.signIn(identity).publicId(), ProviderUnlink.PENDING)
            .orElseThrow()
            .pendingUnlink()
            .orElseThrow();
    }

    private long insertUnlink(String status, final int attempts, Duration dueFromNow) {
        return insertUnlink(status, attempts, dueFromNow, IDENTITY.provider().name(), IDENTITY.subject());
    }

    private long insertUnlink(
        String status,
        final int attempts,
        Duration dueFromNow,
        String provider,
        String subject
    ) {
        OffsetDateTime now = now();
        return jdbcClient.sql("""
                INSERT INTO provider_unlink
                    (provider, subject, status, attempts, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(provider, subject, status, attempts, now.plus(dueFromNow), now, now)
            .query(Long.class)
            .single();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private static List<Long> ids(List<PendingUnlink> unlinks) {
        return unlinks.stream().map(PendingUnlink::id).toList();
    }

    private <T> List<T> runConcurrently(IntFunction<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(index -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index);
                }))
                .toList();
            ready.await();
            start.countDown();
            return futures.stream().map(ProviderUnlinkRetryTest::result).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> T result(Future<T> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long unlinkCount() {
        return jdbcClient.sql("SELECT count(*) FROM provider_unlink").query(Long.class).single();
    }

    private OffsetDateTime nextAttemptAt(final long id) {
        return row(id).nextAttemptAt();
    }

    private Row row(final long id) {
        return jdbcClient.sql("SELECT status, attempts, next_attempt_at, last_error FROM provider_unlink WHERE id = ?")
            .param(id)
            .query((rows, rowNumber) -> new Row(
                rows.getString("status"), rows.getInt("attempts"),
                rows.getObject("next_attempt_at", OffsetDateTime.class), rows.getString("last_error")))
            .single();
    }

    private record Row(
        String status,
        int attempts,
        OffsetDateTime nextAttemptAt,
        String lastError
    ) {
    }
}
