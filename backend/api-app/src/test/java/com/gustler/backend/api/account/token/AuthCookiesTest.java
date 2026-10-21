package com.gustler.backend.api.account.token;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.member.api.IssuedRefreshToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class AuthCookiesTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");

    private final AuthCookies cookies = new AuthCookies(
        new TokenProperties("unit-test-secret-key-unit-test-secret-key", "k1", Duration.ofMinutes(15)),
        Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 액세스_쿠키는_모든_경로로_가고_리프레시_쿠키는_인증_경로에만_간다() {
        // given
        HttpHeaders headers = new HttpHeaders();
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(30)), ZoneOffset.UTC);
        IssuedRefreshToken refresh = new IssuedRefreshToken("refresh-value", expiresAt);

        // when
        cookies.write(headers, "access-value", refresh);

        // then
        List<String> actual = headers.get(HttpHeaders.SET_COOKIE);
        assertSoftly(softly -> {
            softly.assertThat(actual.get(0)).startsWith("__Host-sb_at=access-value")
                .contains("Path=/;", "Max-Age=900", "HttpOnly", "Secure", "SameSite=Lax");
            softly.assertThat(actual.get(1)).startsWith("__Host-sb_rt=refresh-value")
                .contains("Path=/;", "Max-Age=2592000", "HttpOnly", "Secure", "SameSite=Lax");
        });
    }

    @Test
    void 지울_때는_같은_경로로_수명_0인_쿠키를_내린다() {
        // given
        HttpHeaders headers = new HttpHeaders();

        // when
        cookies.clear(headers);

        // then
        List<String> actual = headers.get(HttpHeaders.SET_COOKIE);
        assertSoftly(softly -> {
            softly.assertThat(actual.get(0)).startsWith("__Host-sb_at=;").contains("Path=/;", "Max-Age=0");
            softly.assertThat(actual.get(1)).startsWith("__Host-sb_rt=;").contains("Path=/;", "Max-Age=0");
        });
    }
}
