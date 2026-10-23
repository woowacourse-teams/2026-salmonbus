package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class MemberTableTest {
    private static final String KAKAO = "KAKAO";
    private static final String APPLE = "APPLE";
    private static final String KAKAO_SUBJECT = "4823019577";
    private static final String NICKNAME = "연어Salmon1";
    private static final OffsetDateTime JOINED_AT = OffsetDateTime.parse("2026-10-22T09:00:00+09:00");

    @Autowired
    private JdbcClient jdbcClient;

    @ParameterizedTest
    @ValueSource(strings = {"연어", "Salmon", "연어482913", "abcdefghij"})
    void 닉네임은_한글_완성형_영문_숫자_2자에서_10자면_저장된다(String nickname) {
        // when
        final long actual = insertMember(nickname);

        // then
        assertThat(actual).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"연", "연어 버스", "ㄱㄴ", "연어!", "연어😀"})
    void 닉네임이_규칙을_벗어나면_저장되지_않는다(String nickname) {
        // when
        Throwable caughtFailure = catchThrowable(() -> insertMember(nickname));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_member_nickname");
    }

    @Test
    void 닉네임이_10자를_넘으면_저장되지_않는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> insertMember("열한글자열한글자열한글"));

        // then
        assertThat(caughtFailure).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 영문_대소문자만_다른_닉네임은_중복으로_저장되지_않는다() {
        // given
        insertMember("Salmon");

        // when
        Throwable caughtFailure = catchThrowable(() -> insertMember("salmon"));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ux_member_nickname");
    }

    @Test
    void 같은_제공자_계정은_한_회원에만_연결된다() {
        // given
        insertIdentity(KAKAO, KAKAO_SUBJECT, insertMember("연어하나"));
        final long otherMemberId = insertMember("연어둘");

        // when
        Throwable caughtFailure = catchThrowable(() -> insertIdentity(KAKAO, KAKAO_SUBJECT, otherMemberId));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("pk_member_identity");
    }

    @Test
    void 한_회원에는_제공자_계정이_하나만_연결된다() {
        // given
        final long memberId = insertMember(NICKNAME);
        insertIdentity(KAKAO, KAKAO_SUBJECT, memberId);

        // when
        Throwable caughtFailure = catchThrowable(() -> insertIdentity(APPLE, "apple-subject", memberId));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ux_member_identity_member");
    }

    @Test
    void 제공자가_다르면_같은_사용자_ID도_다른_계정으로_연결된다() {
        // given
        insertIdentity(KAKAO, KAKAO_SUBJECT, insertMember("연어하나"));

        // when
        insertIdentity(APPLE, KAKAO_SUBJECT, insertMember("연어둘"));

        // then
        assertThat(identityCount()).isEqualTo(2);
    }

    @Test
    void 등록되지_않은_제공자는_연결되지_않는다() {
        // given
        final long memberId = insertMember(NICKNAME);

        // when
        Throwable caughtFailure = catchThrowable(() -> insertIdentity("GOOGLE", KAKAO_SUBJECT, memberId));

        // then
        assertThat(caughtFailure).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 회원을_지우면_로그인_수단도_함께_지워진다() {
        // given
        final long memberId = insertMember(NICKNAME);
        insertIdentity(KAKAO, KAKAO_SUBJECT, memberId);

        // when
        jdbcClient.sql("DELETE FROM member WHERE id = ?").param(memberId).update();

        // then
        assertThat(identityCount()).isZero();
    }

    @Test
    void 회원을_지우면_리프레시_토큰_계통도_함께_지워진다() {
        // given
        final long memberId = insertMember(NICKNAME);
        final long otherMemberId = insertMember("연어둘");
        insertRefreshToken(memberId, UUID.randomUUID(), 0);
        insertRefreshToken(memberId, UUID.randomUUID(), 3);
        insertRefreshToken(otherMemberId, UUID.randomUUID(), 0);

        // when
        jdbcClient.sql("DELETE FROM member WHERE id = ?").param(memberId).update();

        // then
        assertThat(refreshTokenCount()).isEqualTo(1);
    }

    @Test
    void 같은_계통은_한_행만_저장된다() {
        // given
        final long memberId = insertMember(NICKNAME);
        UUID familyId = UUID.randomUUID();
        insertRefreshToken(memberId, familyId, 0);

        // when
        Throwable caughtFailure = catchThrowable(() -> insertRefreshToken(memberId, familyId, 1));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("pk_refresh_token");
    }

    @Test
    void 한_회원은_계통을_여러_개_가질_수_있다() {
        // given
        final long memberId = insertMember(NICKNAME);
        insertRefreshToken(memberId, UUID.randomUUID(), 0);

        // when
        insertRefreshToken(memberId, UUID.randomUUID(), 0);

        // then
        assertThat(refreshTokenCount()).isEqualTo(2);
    }

    @Test
    void 세대가_음수이면_저장되지_않는다() {
        // given
        final long memberId = insertMember(NICKNAME);

        // when
        Throwable caughtFailure = catchThrowable(() -> insertRefreshToken(memberId, UUID.randomUUID(), -1));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_refresh_token_generation");
    }

    @Test
    void 없는_회원의_리프레시_토큰은_저장되지_않는다() {
        // when
        Throwable caughtFailure = catchThrowable(
            () -> insertRefreshToken(Long.MAX_VALUE, UUID.randomUUID(), 0));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_refresh_token_member");
    }

    @Test
    void 연결_해제_요청은_회원_없이_제공자_식별자만으로_저장된다() {
        // when
        insertProviderUnlink(KAKAO, "PENDING", 0);

        // then
        assertThat(jdbcClient.sql("SELECT count(*) FROM provider_unlink").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void 등록되지_않은_제공자의_연결_해제_요청은_저장되지_않는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> insertProviderUnlink("GOOGLE", "PENDING", 0));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_provider_unlink_provider");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DONE", "FAILED", "pending"})
    void 연결_해제_요청의_상태는_대기와_포기만_저장된다(String status) {
        // when
        Throwable caughtFailure = catchThrowable(() -> insertProviderUnlink(KAKAO, status, 0));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_provider_unlink_status");
    }

    @Test
    void 연결_해제_시도_횟수가_음수이면_저장되지_않는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> insertProviderUnlink(APPLE, "GAVE_UP", -1));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_provider_unlink_attempts");
    }

    private void insertProviderUnlink(String provider, String status, final int attempts) {
        jdbcClient.sql("""
                INSERT INTO provider_unlink
                    (provider, subject, status, attempts, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
            .params(provider, KAKAO_SUBJECT, status, attempts, JOINED_AT, JOINED_AT, JOINED_AT)
            .update();
    }

    private long insertMember(String nickname) {
        return jdbcClient.sql("""
                INSERT INTO member (public_id, nickname, created_at, last_login_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """)
            .params(UUID.randomUUID(), nickname, JOINED_AT, JOINED_AT)
            .query(Long.class)
            .single();
    }

    private void insertIdentity(String provider, String subject, final long memberId) {
        jdbcClient.sql("""
                INSERT INTO member_identity (provider, subject, member_id, created_at)
                VALUES (?, ?, ?, ?)
                """)
            .params(provider, subject, memberId, JOINED_AT)
            .update();
    }

    private long identityCount() {
        return jdbcClient.sql("SELECT count(*) FROM member_identity").query(Long.class).single();
    }

    private void insertRefreshToken(long memberId, UUID familyId, int generation) {
        jdbcClient.sql("""
                INSERT INTO refresh_token (family_id, member_id, generation, started_at, rotated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(familyId, memberId, generation, JOINED_AT, JOINED_AT, JOINED_AT.plusDays(30))
            .update();
    }

    private long refreshTokenCount() {
        return jdbcClient.sql("SELECT count(*) FROM refresh_token").query(Long.class).single();
    }
}
