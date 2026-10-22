package com.gustler.backend.api.account.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccessTokensTest {
    private static final String SECRET = "unit-test-secret-key-unit-test-secret-key";
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final Instant ISSUED_AT = Instant.parse("2026-10-21T00:00:00Z");

    private final TokenProperties properties = new TokenProperties(SECRET, "k1", Duration.ofMinutes(15));

    @Test
    void 발급한_토큰은_만료_전까지_회원_식별자로_검증된다() {
        // given
        String token = tokensAt(ISSUED_AT).issue(MEMBER);

        // when
        Optional<UUID> justBefore = tokensAt(ISSUED_AT.plus(Duration.ofMinutes(14))).verify(token);
        Optional<UUID> withinSkew = tokensAt(ISSUED_AT.plus(Duration.ofMinutes(15)).plusSeconds(4)).verify(token);

        // then
        assertSoftly(softly -> {
            softly.assertThat(justBefore).contains(MEMBER);
            softly.assertThat(withinSkew).contains(MEMBER);
        });
    }

    @Test
    void 만료와_허용_오차가_지나면_검증에_실패한다() {
        // given
        String token = tokensAt(ISSUED_AT).issue(MEMBER);

        // when
        Optional<UUID> actual = tokensAt(ISSUED_AT.plus(Duration.ofMinutes(15)).plusSeconds(6)).verify(token);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 다른_비밀로_서명했으면_검증에_실패한다() {
        // given
        String token = tokensAt(ISSUED_AT).issue(MEMBER);
        TokenProperties other = new TokenProperties(
            "another-secret-key-another-secret-key-0000", "k1", Duration.ofMinutes(15));

        // when
        Optional<UUID> actual = new AccessTokens(other, Clock.fixed(ISSUED_AT, ZoneOffset.UTC)).verify(token);

        // then
        assertThat(actual).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "garbage", "a.b.c", "eyJhbGciOiJub25lIn0.e30."})
    void 형식이_깨졌거나_서명이_없는_토큰은_검증에_실패한다(String token) {
        // when
        Optional<UUID> actual = tokensAt(ISSUED_AT).verify(token);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 일부를_바꾼_토큰은_검증에_실패한다() {
        // given
        AccessTokens tokens = tokensAt(ISSUED_AT);
        String token = tokens.issue(MEMBER);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "A." + parts[2];

        // when
        Optional<UUID> actual = tokens.verify(tampered);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 비밀이_32바이트보다_짧으면_기동하지_않는다() {
        // when & then
        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> properties("short").requireComplete())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_TOKEN_SECRET");
            softly.assertThatThrownBy(() -> properties(null).requireComplete())
                .isInstanceOf(IllegalStateException.class);
        });
    }

    private AccessTokens tokensAt(Instant instant) {
        return new AccessTokens(properties, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static TokenProperties properties(String secret) {
        return new TokenProperties(secret, "k1", Duration.ofMinutes(15));
    }
}
