package com.gustler.backend.member.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.member.support.MemberIntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@MemberIntegrationTest
class JdbcRefreshTokenRepositoryTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T09:00:00+09:00");
    private static final int FUTURE_FAMILIES = 20_000;
    private static final int MEMBERS = 2_000;
    private static final int FAMILIES_PER_MEMBER = 10;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void 회원을_비운다() {
        jdbcClient.sql("DELETE FROM member").update();
    }

    @Test
    void 만료된_계통이_드물면_정리_쿼리는_만료_인덱스를_탄다() {
        // given
        long memberId = jdbcClient.sql("""
                INSERT INTO member (public_id, nickname, created_at, last_login_at)
                VALUES (gen_random_uuid(), '연어팬', now(), now())
                RETURNING id
                """)
            .query(Long.class)
            .single();
        jdbcClient.sql("""
                INSERT INTO refresh_token (family_id, member_id, generation, started_at, rotated_at, expires_at)
                SELECT gen_random_uuid(), ?, 0, ?, ?, ? + make_interval(secs => g) FROM generate_series(-5, ?) g
                """)
            .params(memberId, NOW, NOW, NOW, FUTURE_FAMILIES)
            .update();
        jdbcClient.sql("ANALYZE refresh_token").update();

        // when
        List<String> plan = jdbcClient.sql("EXPLAIN " + JdbcRefreshTokenRepository.DELETE_EXPIRED)
            .params(NOW, 1_000)
            .query(String.class)
            .list();

        // then
        assertSoftly(softly -> {
            softly.assertThat(String.join("\n", plan)).contains("ix_refresh_token_expires");
            softly.assertThat(String.join("\n", plan)).doesNotContain("Seq Scan on refresh_token");
        });
    }

    @Test
    void 회원의_계통을_모두_지우는_쿼리는_회원_인덱스를_탄다() {
        // given
        jdbcClient.sql("""
                INSERT INTO member (public_id, nickname, created_at, last_login_at)
                SELECT gen_random_uuid(), '회원' || g, now(), now() FROM generate_series(1, ?) g
                """)
            .param(MEMBERS)
            .update();
        jdbcClient.sql("""
                INSERT INTO refresh_token (family_id, member_id, generation, started_at, rotated_at, expires_at)
                SELECT gen_random_uuid(), m.id, 0, ?, ?, ? FROM member m, generate_series(1, ?)
                """)
            .params(NOW, NOW, NOW.plusDays(30), FAMILIES_PER_MEMBER)
            .update();
        jdbcClient.sql("ANALYZE member").update();
        jdbcClient.sql("ANALYZE refresh_token").update();

        // when
        List<String> plan = jdbcClient.sql("EXPLAIN " + JdbcRefreshTokenRepository.DELETE_ALL_OF)
            .param(UUID.randomUUID())
            .query(String.class)
            .list();

        // then
        assertSoftly(softly -> {
            softly.assertThat(String.join("\n", plan)).contains("ix_refresh_token_member");
            softly.assertThat(String.join("\n", plan)).doesNotContain("Seq Scan on refresh_token");
        });
    }

    @Test
    void 정리_쿼리는_상한과_만료_조건을_지킨다() {
        // given
        long memberId = jdbcClient.sql("""
                INSERT INTO member (public_id, nickname, created_at, last_login_at)
                VALUES (gen_random_uuid(), '연어팬', now(), now())
                RETURNING id
                """)
            .query(Long.class)
            .single();
        jdbcClient.sql("""
                INSERT INTO refresh_token (family_id, member_id, generation, started_at, rotated_at, expires_at)
                SELECT gen_random_uuid(), ?, 0, ?, ?, ? + make_interval(secs => g) FROM generate_series(-4, 3) g
                """)
            .params(memberId, NOW, NOW, NOW)
            .update();

        // when
        int deleted = jdbcClient.sql(JdbcRefreshTokenRepository.DELETE_EXPIRED).params(NOW, 3).update();

        // then
        long remaining = jdbcClient.sql("SELECT count(*) FROM refresh_token").query(Long.class).single();
        assertSoftly(softly -> {
            softly.assertThat(deleted).isEqualTo(3);
            softly.assertThat(remaining).isEqualTo(5);
        });
    }
}
