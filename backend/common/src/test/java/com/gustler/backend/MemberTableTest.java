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
    @ValueSource(strings = {"연", "열한글자열한글자열한글", "연어 버스", "ㄱㄴ", "연어!", "연어😀"})
    void 닉네임이_규칙을_벗어나면_저장되지_않는다(String nickname) {
        // when
        Throwable caughtFailure = catchThrowable(() -> insertMember(nickname));

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
}
