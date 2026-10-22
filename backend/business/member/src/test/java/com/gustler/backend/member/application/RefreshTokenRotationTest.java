package com.gustler.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.member.api.IssuedRefreshToken;
import com.gustler.backend.member.api.RefreshResult;
import com.gustler.backend.member.api.RefreshResult.Reason;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.domain.RefreshTokenCodec;
import com.gustler.backend.member.support.AdjustableClock;
import com.gustler.backend.member.support.MemberIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@MemberIntegrationTest
class RefreshTokenRotationTest {
    private static final int CONCURRENT_REQUESTS = 8;
    private static final int RACE_ROUNDS = 20;
    private static final Class<RefreshResult.Rotated> ROTATED = RefreshResult.Rotated.class;
    private static final AtomicInteger NICKNAME_SEQUENCE = new AtomicInteger();

    @Autowired
    private RefreshTokens refreshTokens;

    @Autowired
    private RefreshTokenCodec codec;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void 회원을_비우고_시계를_되돌린다() {
        jdbcClient.sql("DELETE FROM member").update();
        clock.reset();
    }

    @Test
    void 발급하면_계통_한_행이_세대_0으로_저장된다() {
        // given
        UUID publicId = insertMember();

        // when
        IssuedRefreshToken actual = refreshTokens.issue(publicId);

        // then
        Row row = row(familyOf(actual.token()));
        assertSoftly(softly -> {
            softly.assertThat(tokenCount()).isEqualTo(1);
            softly.assertThat(row.generation()).isZero();
            softly.assertThat(generationOf(actual.token())).isZero();
            softly.assertThat(row.startedAt().toInstant()).isEqualTo(clock.instant());
            softly.assertThat(row.rotatedAt().toInstant()).isEqualTo(clock.instant());
        });
    }

    @Test
    void 테이블에는_토큰_원문도_해시도_저장하지_않는다() {
        // when
        List<String> actual = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'refresh_token'
                """)
            .query(String.class)
            .list();

        // then
        assertThat(actual)
            .containsExactlyInAnyOrder(
                "family_id", "member_id", "generation", "started_at", "rotated_at", "expires_at");
    }

    @Test
    void 발급한_토큰은_발급_시각의_30일_뒤에_만료된다() {
        // given
        UUID publicId = insertMember();

        // when
        IssuedRefreshToken actual = refreshTokens.issue(publicId);

        // then
        OffsetDateTime issuedAt = OffsetDateTime.ofInstant(clock.instant(), clock.getZone());
        assertSoftly(softly -> {
            softly.assertThat(actual.expiresAt().toInstant()).isEqualTo(issuedAt.plusDays(30).toInstant());
            softly.assertThat(row(familyOf(actual.token())).expiresAt().toInstant())
                .isEqualTo(actual.expiresAt().toInstant());
        });
    }

    @Test
    void 발급할_때마다_새_계통이다() {
        // given
        UUID publicId = insertMember();

        // when
        IssuedRefreshToken first = refreshTokens.issue(publicId);
        IssuedRefreshToken second = refreshTokens.issue(publicId);

        // then
        assertSoftly(softly -> {
            softly.assertThat(first.token()).isNotEqualTo(second.token());
            softly.assertThat(familyOf(first.token())).isNotEqualTo(familyOf(second.token()));
            softly.assertThat(tokenCount()).isEqualTo(2);
        });
    }

    @Test
    void 회원이_없으면_발급하지_못하고_아무것도_저장하지_않는다() {
        // when & then
        assertThatThrownBy(() -> refreshTokens.issue(UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(tokenCount()).isZero();
    }

    @Test
    void 갱신하면_같은_행의_세대가_하나_오르고_만료가_다시_30일이다() {
        // given
        UUID publicId = insertMember();
        IssuedRefreshToken issued = refreshTokens.issue(publicId);
        clock.advance(Duration.ofDays(20));

        // when
        RefreshResult actual = refreshTokens.rotate(issued.token());

        // then
        RefreshResult.Rotated rotated = (RefreshResult.Rotated) actual;
        Row row = row(familyOf(issued.token()));
        OffsetDateTime rotatedAt = OffsetDateTime.ofInstant(clock.instant(), clock.getZone());
        assertSoftly(softly -> {
            softly.assertThat(rotated.publicId()).isEqualTo(publicId);
            softly.assertThat(rotated.next().token()).isNotEqualTo(issued.token());
            softly.assertThat(familyOf(rotated.next().token())).isEqualTo(familyOf(issued.token()));
            softly.assertThat(generationOf(rotated.next().token())).isEqualTo(1);
            softly.assertThat(rotated.next().expiresAt().toInstant()).isEqualTo(rotatedAt.plusDays(30).toInstant());
            softly.assertThat(row.generation()).isEqualTo(1);
            softly.assertThat(row.startedAt().toInstant()).isEqualTo(AdjustableClock.INITIAL);
            softly.assertThat(row.rotatedAt().toInstant()).isEqualTo(clock.instant());
            softly.assertThat(row.expiresAt().toInstant()).isEqualTo(rotated.next().expiresAt().toInstant());
            softly.assertThat(tokenCount()).isEqualTo(1);
        });
    }

    @Test
    void 새_토큰으로_이어서_계속_갱신할_수_있다() {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        String token = issued.token();

        // when
        for (int round = 1; round <= 3; round++) {
            clock.advance(Duration.ofHours(1));
            token = ((RefreshResult.Rotated) refreshTokens.rotate(token)).next().token();
        }

        // then
        String latest = token;
        assertSoftly(softly -> {
            softly.assertThat(generationOf(latest)).isEqualTo(3);
            softly.assertThat(row(familyOf(latest)).generation()).isEqualTo(3);
            softly.assertThat(tokenCount()).isEqualTo(1);
        });
    }

    @Test
    void 처음_로그인한_뒤_180일에_가까워지면_갱신해도_만료가_180일째로_잘린다() {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        String token = rotateEvery28Days(issued.token(), 5);
        clock.advance(Duration.ofDays(20));

        // when
        RefreshResult actual = refreshTokens.rotate(token);

        // then
        RefreshResult.Rotated rotated = (RefreshResult.Rotated) actual;
        Instant maxExpiresAt = AdjustableClock.INITIAL.plus(Duration.ofDays(180));
        assertSoftly(softly -> {
            softly.assertThat(generationOf(rotated.next().token())).isEqualTo(6);
            softly.assertThat(rotated.next().expiresAt().toInstant()).isEqualTo(maxExpiresAt);
            softly.assertThat(row(familyOf(token)).expiresAt().toInstant()).isEqualTo(maxExpiresAt);
        });
    }

    @Test
    void 처음_로그인한_뒤_180일이_되면_갱신하지_못하고_만료로_거절한다() {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        String token = rotateEvery28Days(issued.token(), 5);
        clock.advance(Duration.ofDays(20));
        token = ((RefreshResult.Rotated) refreshTokens.rotate(token)).next().token();
        clock.advance(Duration.ofDays(20));

        // when
        RefreshResult actual = refreshTokens.rotate(token);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(new RefreshResult.Rejected(Reason.EXPIRED));
            softly.assertThat(tokenCount()).isZero();
        });
    }

    @Test
    void 모르는_토큰과_깨진_토큰은_알_수_없음으로_거절한다() {
        // given
        refreshTokens.issue(insertMember());
        String unknownFamily = codec.encode(UUID.randomUUID(), 0);

        // when
        List<RefreshResult> actual = List.of(
            refreshTokens.rotate(unknownFamily), refreshTokens.rotate("never-issued-token"),
            refreshTokens.rotate(""), refreshTokens.rotate(" "), refreshTokens.rotate(null));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).containsOnly(new RefreshResult.Rejected(Reason.UNKNOWN));
            softly.assertThat(tokenCount()).isEqualTo(1);
        });
    }

    @Test
    void 서명이_틀린_토큰은_알_수_없음으로_거절하고_계통을_건드리지_않는다() {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        String forged = codec.encode(familyOf(issued.token()), 1).replaceAll("[A-Za-z0-9_-]{43}$", "A".repeat(43));

        // when
        RefreshResult actual = refreshTokens.rotate(forged);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(new RefreshResult.Rejected(Reason.UNKNOWN));
            softly.assertThat(row(familyOf(issued.token())).generation()).isZero();
        });
    }

    @Test
    void 아직_발급하지_않은_세대의_토큰은_알_수_없음으로_거절한다() {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        String future = codec.encode(familyOf(issued.token()), 5);

        // when
        RefreshResult actual = refreshTokens.rotate(future);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(new RefreshResult.Rejected(Reason.UNKNOWN));
            softly.assertThat(row(familyOf(issued.token())).generation()).isZero();
        });
    }

    @Test
    void 만료된_토큰은_만료로_거절하고_계통을_지운다() {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        clock.advance(Duration.ofDays(30));

        // when
        RefreshResult actual = refreshTokens.rotate(issued.token());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(new RefreshResult.Rejected(Reason.EXPIRED));
            softly.assertThat(tokenCount()).isZero();
        });
    }

    @Test
    void 만료_하루_전까지는_갱신할_수_있다() {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        clock.advance(Duration.ofDays(29));

        // when
        RefreshResult actual = refreshTokens.rotate(issued.token());

        // then
        assertThat(actual).isInstanceOf(ROTATED);
    }

    @Test
    void 오래된_세대의_토큰도_계통이_만료됐으면_재사용이_아니라_만료로_거절한다() {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        refreshTokens.rotate(issued.token());
        clock.advance(Duration.ofDays(31));

        // when
        RefreshResult actual = refreshTokens.rotate(issued.token());

        // then
        assertThat(actual).isEqualTo(new RefreshResult.Rejected(Reason.EXPIRED));
    }

    @Test
    void 한_세대_전_토큰이_10초_안에_다시_오면_상태를_바꾸지_않고_같은_현재_토큰을_돌려준다() {
        // given
        UUID publicId = insertMember();
        IssuedRefreshToken issued = refreshTokens.issue(publicId);
        RefreshResult.Rotated first = (RefreshResult.Rotated) refreshTokens.rotate(issued.token());
        Row before = row(familyOf(issued.token()));
        clock.advance(Duration.ofSeconds(10));

        // when
        RefreshResult actual = refreshTokens.rotate(issued.token());

        // then
        RefreshResult.Rotated second = (RefreshResult.Rotated) actual;
        assertSoftly(softly -> {
            softly.assertThat(second.publicId()).isEqualTo(publicId);
            softly.assertThat(second.next()).isEqualTo(first.next());
            softly.assertThat(row(familyOf(issued.token()))).isEqualTo(before);
            softly.assertThat(tokenCount()).isEqualTo(1);
        });
    }

    @Test
    void 한_세대_전_토큰이_10초를_넘겨_오면_재사용으로_거절하고_계통을_지운다() {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        RefreshResult.Rotated first = (RefreshResult.Rotated) refreshTokens.rotate(issued.token());
        clock.advance(Duration.ofSeconds(10).plusNanos(1_000));

        // when
        RefreshResult actual = refreshTokens.rotate(issued.token());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(new RefreshResult.Rejected(Reason.REUSED));
            softly.assertThat(tokenCount()).isZero();
            softly.assertThat(refreshTokens.rotate(first.next().token()))
                .isEqualTo(new RefreshResult.Rejected(Reason.UNKNOWN));
            softly.assertThat(refreshTokens.rotate(issued.token()))
                .isEqualTo(new RefreshResult.Rejected(Reason.UNKNOWN));
        });
    }

    @Test
    void 두_세대_전_토큰은_방금_갱신했어도_재사용으로_거절한다() {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        RefreshResult.Rotated first = (RefreshResult.Rotated) refreshTokens.rotate(issued.token());
        refreshTokens.rotate(first.next().token());

        // when
        RefreshResult actual = refreshTokens.rotate(issued.token());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(new RefreshResult.Rejected(Reason.REUSED));
            softly.assertThat(tokenCount()).isZero();
        });
    }

    @Test
    void 재사용을_감지해도_다른_계통의_토큰은_그대로다() {
        // given
        UUID publicId = insertMember();
        IssuedRefreshToken stolen = refreshTokens.issue(publicId);
        IssuedRefreshToken otherDevice = refreshTokens.issue(publicId);
        refreshTokens.rotate(stolen.token());
        clock.advance(Duration.ofMinutes(1));

        // when
        refreshTokens.rotate(stolen.token());

        // then
        assertSoftly(softly -> {
            softly.assertThat(tokenCount()).isEqualTo(1);
            softly.assertThat(refreshTokens.rotate(otherDevice.token())).isInstanceOf(ROTATED);
        });
    }

    @Test
    void 같은_토큰으로_동시에_갱신하면_모두_같은_새_토큰을_받고_세대는_하나만_오른다() throws Exception {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());

        // when
        List<RefreshResult> actual = runConcurrently(index -> refreshTokens.rotate(issued.token()));

        // then
        List<IssuedRefreshToken> nextTokens = actual.stream()
            .map(result -> ((RefreshResult.Rotated) result).next())
            .toList();
        assertSoftly(softly -> {
            softly.assertThat(actual).allSatisfy(result -> assertThat(result).isInstanceOf(ROTATED));
            softly.assertThat(nextTokens.stream().distinct()).hasSize(1);
            softly.assertThat(generationOf(nextTokens.get(0).token())).isEqualTo(1);
            softly.assertThat(row(familyOf(issued.token())).generation()).isEqualTo(1);
            softly.assertThat(tokenCount()).isEqualTo(1);
        });
    }

    @Test
    void 서로_다른_계통은_동시에_갱신해도_서로_영향이_없다() throws Exception {
        // given
        UUID publicId = insertMember();
        List<IssuedRefreshToken> issued = IntStream.range(0, CONCURRENT_REQUESTS)
            .mapToObj(index -> refreshTokens.issue(publicId))
            .toList();

        // when
        List<RefreshResult> actual = runConcurrently(index -> refreshTokens.rotate(issued.get(index).token()));

        // then
        List<Integer> generations = issued.stream()
            .map(token -> row(familyOf(token.token())).generation())
            .toList();
        assertSoftly(softly -> {
            softly.assertThat(actual).allSatisfy(result -> assertThat(result).isInstanceOf(ROTATED));
            softly.assertThat(generations).containsOnly(1);
            softly.assertThat(tokenCount()).isEqualTo(CONCURRENT_REQUESTS);
        });
    }

    @Test
    void 갱신과_폐기가_동시에_와도_그_계통에는_행이_남지_않는다() throws Exception {
        // given
        UUID publicId = insertMember();

        // when
        for (int round = 0; round < RACE_ROUNDS; round++) {
            IssuedRefreshToken issued = refreshTokens.issue(publicId);
            runConcurrently(index -> raceRotateAndRevoke(index, issued.token()), 2);
        }

        // then
        assertThat(tokenCount()).isZero();
    }

    @Test
    void 유예_안의_재제시와_다음_세대_갱신이_겹치면_재사용_감지_여부에_따라_상태가_일관된다() throws Exception {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        RefreshResult.Rotated first = (RefreshResult.Rotated) refreshTokens.rotate(issued.token());
        UUID familyId = familyOf(issued.token());

        // when
        List<RefreshResult> actual = runConcurrently(index -> rotateOldOrCurrent(index, issued, first));

        // then
        RefreshResult reused = new RefreshResult.Rejected(Reason.REUSED);
        RefreshResult unknown = new RefreshResult.Rejected(Reason.UNKNOWN);
        RefreshResult atFirst = rotatedTo(first, first.next().token());
        RefreshResult atSecond = rotatedTo(first, codec.encode(familyId, 2));
        long expectedRows = 1;
        boolean unknownAllowed = false;
        if (actual.contains(reused)) {
            expectedRows = 0;
            unknownAllowed = true;
        }
        long finalExpectedRows = expectedRows;
        boolean finalUnknownAllowed = unknownAllowed;
        assertSoftly(softly -> {
            softly.assertThat(actual).allSatisfy(result -> assertThat(result).isIn(reused, unknown, atFirst, atSecond));
            softly.assertThat(finalUnknownAllowed || !actual.contains(unknown))
                .as("재사용을 감지하지 않았다면 알 수 없음이 나오면 안 된다")
                .isTrue();
            softly.assertThat(tokenCount()).isEqualTo(finalExpectedRows);
        });
    }

    @Test
    void 폐기하면_세대와_상관없이_그_계통이_사라지고_다른_계통은_그대로다() {
        // given
        UUID publicId = insertMember();
        IssuedRefreshToken issued = refreshTokens.issue(publicId);
        RefreshResult.Rotated rotated = (RefreshResult.Rotated) refreshTokens.rotate(issued.token());
        IssuedRefreshToken otherDevice = refreshTokens.issue(publicId);

        // when
        refreshTokens.revoke(issued.token());

        // then
        assertSoftly(softly -> {
            softly.assertThat(tokenCount()).isEqualTo(1);
            softly.assertThat(refreshTokens.rotate(rotated.next().token()))
                .isEqualTo(new RefreshResult.Rejected(Reason.UNKNOWN));
            softly.assertThat(refreshTokens.rotate(otherDevice.token())).isInstanceOf(ROTATED);
        });
    }

    @Test
    void 서명이_틀리거나_모르는_토큰을_폐기해도_아무_일도_일어나지_않는다() {
        // given
        IssuedRefreshToken issued = refreshTokens.issue(insertMember());
        String forged = codec.encode(familyOf(issued.token()), 0).replaceAll("[A-Za-z0-9_-]{43}$", "A".repeat(43));

        // when
        refreshTokens.revoke(forged);
        refreshTokens.revoke(codec.encode(UUID.randomUUID(), 0));
        refreshTokens.revoke("never-issued-token");
        refreshTokens.revoke(" ");
        refreshTokens.revoke(null);

        // then
        assertThat(tokenCount()).isEqualTo(1);
    }

    @Test
    void 회원의_계통을_모두_폐기하면_어느_기기의_토큰도_갱신할_수_없고_다른_회원은_그대로다() {
        // given
        UUID publicId = insertMember();
        IssuedRefreshToken phone = refreshTokens.issue(publicId);
        IssuedRefreshToken laptopFirst = refreshTokens.issue(publicId);
        RefreshResult.Rotated laptop = (RefreshResult.Rotated) refreshTokens.rotate(laptopFirst.token());
        IssuedRefreshToken otherMember = refreshTokens.issue(insertMember());

        // when
        int actual = refreshTokens.revokeAll(publicId);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(2);
            softly.assertThat(tokenCount()).isEqualTo(1);
            softly.assertThat(refreshTokens.rotate(phone.token()))
                .isEqualTo(new RefreshResult.Rejected(Reason.UNKNOWN));
            softly.assertThat(refreshTokens.rotate(laptop.next().token()))
                .isEqualTo(new RefreshResult.Rejected(Reason.UNKNOWN));
            softly.assertThat(refreshTokens.rotate(otherMember.token())).isInstanceOf(ROTATED);
        });
    }

    @Test
    void 계통이_없거나_없는_회원의_계통을_모두_폐기하면_0이다() {
        // given
        UUID withoutFamily = insertMember();
        refreshTokens.issue(insertMember());

        // when
        int withoutFamilyRevoked = refreshTokens.revokeAll(withoutFamily);
        int unknownRevoked = refreshTokens.revokeAll(UUID.randomUUID());

        // then
        assertSoftly(softly -> {
            softly.assertThat(withoutFamilyRevoked).isZero();
            softly.assertThat(unknownRevoked).isZero();
            softly.assertThat(tokenCount()).isEqualTo(1);
        });
    }

    @Test
    void 정리하면_만료된_계통만_상한까지_지우고_두_번째_호출이_나머지를_지운다() {
        // given
        long memberId = insertMemberRow();
        final int expired = RefreshTokens.PURGE_BATCH_SIZE + 5;
        insertFamilies(memberId, expired, Duration.ofMinutes(-1));
        insertFamilies(memberId, 3, Duration.ofMinutes(1));

        // when
        int first = refreshTokens.purgeObsolete();
        long afterFirst = tokenCount();
        int second = refreshTokens.purgeObsolete();
        int third = refreshTokens.purgeObsolete();

        // then
        assertSoftly(softly -> {
            softly.assertThat(first).isEqualTo(RefreshTokens.PURGE_BATCH_SIZE);
            softly.assertThat(afterFirst).isEqualTo(8);
            softly.assertThat(second).isEqualTo(5);
            softly.assertThat(third).isZero();
            softly.assertThat(tokenCount()).isEqualTo(3);
        });
    }

    @Test
    void 만료_시각_정각인_계통은_정리하지_않는다() {
        // given
        long memberId = insertMemberRow();
        insertFamilies(memberId, 1, Duration.ZERO);

        // when
        int actual = refreshTokens.purgeObsolete();

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isZero();
            softly.assertThat(tokenCount()).isEqualTo(1);
        });
    }

    private Object raceRotateAndRevoke(int index, String token) {
        if (index == 0) {
            return refreshTokens.rotate(token);
        }
        refreshTokens.revoke(token);
        return token;
    }

    private RefreshResult rotateOldOrCurrent(int index, IssuedRefreshToken old, RefreshResult.Rotated current) {
        if (index % 2 == 0) {
            return refreshTokens.rotate(old.token());
        }
        return refreshTokens.rotate(current.next().token());
    }

    /** 시계가 멈춰 있어 어느 세대든 만료 시각이 같다. */
    private static RefreshResult rotatedTo(RefreshResult.Rotated reference, String token) {
        IssuedRefreshToken issued = new IssuedRefreshToken(token, reference.next().expiresAt());
        return new RefreshResult.Rotated(reference.publicId(), issued);
    }

    private <T> List<T> runConcurrently(IntFunction<T> task) throws Exception {
        return runConcurrently(task, CONCURRENT_REQUESTS);
    }

    private <T> List<T> runConcurrently(IntFunction<T> task, int requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        try {
            CountDownLatch ready = new CountDownLatch(requests);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = IntStream.range(0, requests)
                .mapToObj(index -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index);
                }))
                .toList();
            ready.await();
            start.countDown();
            return futures.stream().map(RefreshTokenRotationTest::result).toList();
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

    private UUID insertMember() {
        UUID publicId = UUID.randomUUID();
        insertMemberRow(publicId);
        return publicId;
    }

    private long insertMemberRow() {
        return insertMemberRow(UUID.randomUUID());
    }

    private long insertMemberRow(UUID publicId) {
        return jdbcClient.sql("""
                INSERT INTO member (public_id, nickname, created_at, last_login_at)
                VALUES (?, ?, now(), now())
                RETURNING id
                """)
            .params(publicId, "회원" + NICKNAME_SEQUENCE.incrementAndGet())
            .query(Long.class)
            .single();
    }

    private void insertFamilies(long memberId, int count, Duration expiresFromNow) {
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(clock.instant(), clock.getZone()).plus(expiresFromNow);
        jdbcClient.sql("""
                INSERT INTO refresh_token (family_id, member_id, generation, started_at, rotated_at, expires_at)
                SELECT gen_random_uuid(), ?, 0, ?, ?, ? FROM generate_series(1, ?)
                """)
            .params(memberId, expiresAt.minusDays(30), expiresAt.minusDays(30), expiresAt, count)
            .update();
    }

    private String rotateEvery28Days(String token, int rounds) {
        String current = token;
        for (int round = 1; round <= rounds; round++) {
            clock.advance(Duration.ofDays(28));
            current = ((RefreshResult.Rotated) refreshTokens.rotate(current)).next().token();
        }
        return current;
    }

    private UUID familyOf(String token) {
        return codec.decode(token).orElseThrow().familyId();
    }

    private int generationOf(String token) {
        return codec.decode(token).orElseThrow().generation();
    }

    private Row row(UUID familyId) {
        return jdbcClient.sql("""
                SELECT generation, started_at, rotated_at, expires_at FROM refresh_token WHERE family_id = ?
                """)
            .param(familyId)
            .query((rows, rowNumber) -> new Row(
                rows.getInt("generation"), rows.getObject("started_at", OffsetDateTime.class),
                rows.getObject("rotated_at", OffsetDateTime.class), rows.getObject("expires_at", OffsetDateTime.class)))
            .single();
    }

    private long tokenCount() {
        return jdbcClient.sql("SELECT count(*) FROM refresh_token").query(Long.class).single();
    }

    private record Row(
        int generation,
        OffsetDateTime startedAt,
        OffsetDateTime rotatedAt,
        OffsetDateTime expiresAt
    ) {
    }
}
