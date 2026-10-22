package com.gustler.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.PendingUnlink;
import com.gustler.backend.member.api.ProviderUnlink;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.member.api.ViewMember;
import com.gustler.backend.member.api.WithdrawMember;
import com.gustler.backend.member.api.WithdrawnMember;
import com.gustler.backend.member.domain.ProviderUnlinkPolicy;
import com.gustler.backend.member.support.AdjustableClock;
import com.gustler.backend.member.support.MemberIntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
class MemberWithdrawalTest {
    private static final int CONCURRENT_REQUESTS = 8;
    private static final String SUBJECT = "kakao-withdraw";
    private static final MemberIdentity IDENTITY = new MemberIdentity(LoginProvider.KAKAO, SUBJECT);

    @Autowired
    private WithdrawMember withdrawMember;

    @Autowired
    private SignInWithSocialAccount signIn;

    @Autowired
    private RefreshTokens refreshTokens;

    @Autowired
    private ViewMember viewMember;

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
    void 제공자_계정을_조회한다() {
        // given
        UUID publicId = register(LoginProvider.APPLE, "apple-1");

        // when
        Optional<MemberIdentity> actual = withdrawMember.identityOf(publicId);

        // then
        assertThat(actual).contains(new MemberIdentity(LoginProvider.APPLE, "apple-1"));
    }

    @Test
    void 없는_회원의_제공자_계정은_비어_있다() {
        // when
        Optional<MemberIdentity> actual = withdrawMember.identityOf(UUID.randomUUID());

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 탈퇴하면_회원과_제공자_계정과_리프레시_토큰이_지워지고_다른_회원은_그대로다() {
        // given
        UUID publicId = register(LoginProvider.KAKAO, SUBJECT);
        UUID otherId = register(LoginProvider.KAKAO, "kakao-other");
        refreshTokens.issue(publicId);
        refreshTokens.issue(publicId);
        refreshTokens.issue(otherId);

        // when
        Optional<WithdrawnMember> actual = withdrawMember.withdraw(publicId, ProviderUnlink.DONE);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).contains(new WithdrawnMember(IDENTITY, Optional.empty()));
            softly.assertThat(viewMember.view(publicId)).isEmpty();
            softly.assertThat(withdrawMember.identityOf(publicId)).isEmpty();
            softly.assertThat(count("SELECT count(*) FROM member_identity WHERE subject = ?", SUBJECT)).isZero();
            softly.assertThat(viewMember.view(otherId)).isPresent();
            softly.assertThat(count("SELECT count(*) FROM member_identity")).isEqualTo(1);
            softly.assertThat(count("SELECT count(*) FROM refresh_token")).isEqualTo(1);
            softly.assertThat(count("""
                    SELECT count(*) FROM refresh_token t JOIN member m ON m.id = t.member_id
                    WHERE m.public_id = ?
                    """, otherId)).isEqualTo(1);
        });
    }

    @Test
    void 연결_해제를_기다리면_호출자가_임대한_대기_요청_하나를_기록한다() {
        // given
        UUID publicId = register(LoginProvider.KAKAO, SUBJECT);

        // when
        Optional<WithdrawnMember> actual = withdrawMember.withdraw(publicId, ProviderUnlink.PENDING);

        // then
        PendingUnlink pending = actual.orElseThrow().pendingUnlink().orElseThrow();
        Row row = row(pending.id());
        assertSoftly(softly -> {
            softly.assertThat(actual.orElseThrow().identity()).isEqualTo(IDENTITY);
            softly.assertThat(pending.identity()).isEqualTo(IDENTITY);
            softly.assertThat(pending.attempts()).isZero();
            softly.assertThat(count("SELECT count(*) FROM provider_unlink")).isEqualTo(1);
            softly.assertThat(row.identity()).isEqualTo(IDENTITY);
            softly.assertThat(row.status()).isEqualTo("PENDING");
            softly.assertThat(row.attempts()).isZero();
            softly.assertThat(row.nextAttemptAt().toInstant())
                .isEqualTo(clock.instant().plus(ProviderUnlinkPolicy.LEASE));
            softly.assertThat(row.lastError()).isNull();
        });
    }

    @Test
    void 이미_연결을_끊었으면_요청을_기록하지_않는다() {
        // given
        UUID publicId = register(LoginProvider.KAKAO, SUBJECT);

        // when
        Optional<WithdrawnMember> actual = withdrawMember.withdraw(publicId, ProviderUnlink.DONE);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.orElseThrow().pendingUnlink()).isEmpty();
            softly.assertThat(count("SELECT count(*) FROM provider_unlink")).isZero();
        });
    }

    @Test
    void 없는_회원이면_결과가_비어_있고_아무것도_기록하지_않는다() {
        // when
        Optional<WithdrawnMember> actual = withdrawMember.withdraw(UUID.randomUUID(), ProviderUnlink.PENDING);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEmpty();
            softly.assertThat(count("SELECT count(*) FROM provider_unlink")).isZero();
        });
    }

    @Test
    void 연결_해제_상태가_없으면_회원을_지우지_않는다() {
        // given
        UUID publicId = register(LoginProvider.KAKAO, SUBJECT);

        // when & then
        assertThatThrownBy(() -> withdrawMember.withdraw(publicId, null)).isInstanceOf(NullPointerException.class);
        assertThat(viewMember.view(publicId)).isPresent();
    }

    @Test
    void 같은_회원이_동시에_탈퇴하면_한_번만_지워지고_대기_요청은_하나다() throws Exception {
        // given
        UUID publicId = register(LoginProvider.KAKAO, SUBJECT);
        refreshTokens.issue(publicId);

        // when
        List<Optional<WithdrawnMember>> actual = runConcurrently(
            index -> withdrawMember.withdraw(publicId, ProviderUnlink.PENDING));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.stream().filter(Optional::isPresent)).hasSize(1);
            softly.assertThat(actual.stream().filter(Optional::isEmpty)).hasSize(CONCURRENT_REQUESTS - 1);
            softly.assertThat(count("SELECT count(*) FROM provider_unlink")).isEqualTo(1);
            softly.assertThat(count("SELECT count(*) FROM member")).isZero();
            softly.assertThat(count("SELECT count(*) FROM refresh_token")).isZero();
        });
    }

    private UUID register(LoginProvider provider, String subject) {
        return signIn.signIn(new SocialIdentity(provider, subject)).publicId();
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
            return futures.stream().map(MemberWithdrawalTest::result).toList();
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

    private long count(String sql, Object... params) {
        return jdbcClient.sql(sql).params(params).query(Long.class).single();
    }

    private Row row(long id) {
        return jdbcClient.sql("""
                SELECT provider, subject, status, attempts, next_attempt_at, last_error
                FROM provider_unlink WHERE id = ?
                """)
            .param(id)
            .query((rows, rowNumber) -> new Row(
                new MemberIdentity(LoginProvider.valueOf(rows.getString("provider")), rows.getString("subject")),
                rows.getString("status"), rows.getInt("attempts"),
                rows.getObject("next_attempt_at", OffsetDateTime.class), rows.getString("last_error")))
            .single();
    }

    private record Row(
        MemberIdentity identity,
        String status,
        int attempts,
        OffsetDateTime nextAttemptAt,
        String lastError
    ) {
    }
}
