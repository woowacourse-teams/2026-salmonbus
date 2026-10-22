package com.gustler.backend.api.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.TokenProperties;
import com.gustler.backend.member.api.ChangeNickname;
import com.gustler.backend.member.api.IssuedRefreshToken;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.support.PostgresTestContainer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 리프레시 토큰으로 로그인을 이어 가고 끝내는 API를 실제 톰캣으로 검증한다. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "auth.enabled=true")
@Import(PostgresTestContainer.class)
class TokenControllerTest {
    private static final String ACCESS_COOKIE = "__Host-sb_at";
    private static final String REFRESH_COOKIE = "__Host-sb_rt";
    private static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SignInWithSocialAccount signIn;

    @Autowired
    private ChangeNickname changeNickname;

    @Autowired
    private RefreshTokens refreshTokens;

    @Autowired
    private AccessTokens accessTokens;

    @Autowired
    private TokenProperties tokenProperties;

    private UUID memberId;
    private String refreshToken;
    private String csrfToken;

    @BeforeEach
    void 회원과_리프레시_토큰을_만든다() throws Exception {
        jdbcClient.sql("DELETE FROM member").update();
        memberId = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "kakao-1")).publicId();
        changeNickname.change(memberId, "연어팬");
        IssuedRefreshToken issued = refreshTokens.issue(memberId);
        refreshToken = issued.token();
        csrfToken = csrfToken();
    }

    @Test
    void 쿠키로_갱신하면_새_토큰을_쿠키로_내리고_204로_답한다() throws Exception {
        // when
        HttpResponse<String> actual = refreshWithCookie(refreshToken);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(setCookie(actual, ACCESS_COOKIE)).contains("HttpOnly", "Secure", "Max-Age=900");
            softly.assertThat(setCookie(actual, REFRESH_COOKIE)).contains("HttpOnly", "Secure", "Path=/;");
            softly.assertThat(cookieValue(actual, REFRESH_COOKIE)).isNotEqualTo(refreshToken);
            softly.assertThat(accessTokens.verify(cookieValue(actual, ACCESS_COOKIE))).contains(memberId);
        });
    }

    @Test
    void 본문으로_갱신하면_새_토큰을_JSON으로_돌려주고_쿠키는_내리지_않는다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/auth/refresh")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"" + refreshToken + "\"}")));

        // then
        JsonNode body = objectMapper.readTree(actual.body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(actual.headers().allValues("Set-Cookie")).isEmpty();
            softly.assertThat(accessTokens.verify(body.path("accessToken").asString())).contains(memberId);
            softly.assertThat(body.path("expiresIn").asLong()).isEqualTo(900);
            softly.assertThat(body.path("refreshToken").asString()).isNotBlank().isNotEqualTo(refreshToken);
            softly.assertThat(body.path("refreshTokenExpiresAt").asString()).isNotBlank();
        });
    }

    @Test
    void 쿠키로_갱신할_때_CSRF_토큰이_없으면_400이다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/auth/refresh")
            .header("Cookie", REFRESH_COOKIE + "=" + refreshToken)
            .POST(HttpRequest.BodyPublishers.noBody()));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo("CSRF_REJECTED");
        });
    }

    @Test
    void 모르는_토큰이면_401이고_쿠키로_보냈다면_인증_쿠키를_지운다() throws Exception {
        // when
        HttpResponse<String> actual = refreshWithCookie("unknown-token");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
            softly.assertThat(setCookie(actual, ACCESS_COOKIE)).contains("Max-Age=0");
            softly.assertThat(setCookie(actual, REFRESH_COOKIE)).contains("Max-Age=0");
        });
    }

    @Test
    void 본문으로_보낸_토큰이_거절되면_401이고_인증_쿠키는_건드리지_않는다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/auth/refresh")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"unknown-token\"}")));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
            softly.assertThat(actual.headers().allValues("Set-Cookie")).isEmpty();
        });
    }

    @Test
    void 본문이_JSON이_아니면_400_INVALID_REQUEST다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/auth/refresh")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("not json")));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo("INVALID_REQUEST");
            softly.assertThat(jdbcClient.sql("SELECT generation FROM refresh_token").query(Integer.class).single())
                .isZero();
        });
    }

    @Test
    void 토큰이_전혀_없으면_401이다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/auth/refresh").POST(HttpRequest.BodyPublishers.noBody()));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void 같은_토큰을_곧바로_다시_보내면_같은_새_리프레시_토큰을_돌려준다() throws Exception {
        // when
        HttpResponse<String> first = refreshWithCookie(refreshToken);
        HttpResponse<String> second = refreshWithCookie(refreshToken);

        // then
        assertSoftly(softly -> {
            softly.assertThat(first.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(second.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(cookieValue(second, REFRESH_COOKIE)).isEqualTo(cookieValue(first, REFRESH_COOKIE));
            softly.assertThat(jdbcClient.sql("SELECT count(*) FROM refresh_token").query(Long.class).single())
                .isEqualTo(1);
        });
    }

    @Test
    void 이전_세대_토큰이_유예_시간_뒤에_다시_오면_계통의_토큰이_모두_못_쓰게_된다() throws Exception {
        // given
        HttpResponse<String> first = refreshWithCookie(refreshToken);
        String newer = cookieValue(first, REFRESH_COOKIE);
        jdbcClient.sql("UPDATE refresh_token SET rotated_at = rotated_at - interval '1 minute'").update();

        // when
        HttpResponse<String> reused = refreshWithCookie(refreshToken);
        HttpResponse<String> afterwards = refreshWithCookie(newer);

        // then
        assertSoftly(softly -> {
            softly.assertThat(reused.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(afterwards.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(jdbcClient.sql("SELECT count(*) FROM refresh_token").query(Long.class).single()).isZero();
        });
    }

    @Test
    void 탈퇴한_회원의_토큰으로는_갱신할_수_없다() throws Exception {
        // given
        jdbcClient.sql("DELETE FROM member").update();

        // when
        HttpResponse<String> actual = refreshWithCookie(refreshToken);

        // then
        assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void 로그아웃하면_리프레시_토큰을_폐기하고_쿠키를_지우고_204다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/auth/logout")
            .header("Cookie", cookies(REFRESH_COOKIE + "=" + refreshToken))
            .header("X-XSRF-TOKEN", csrfToken)
            .POST(HttpRequest.BodyPublishers.noBody()));
        HttpResponse<String> afterwards = refreshWithCookie(refreshToken);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(setCookie(actual, ACCESS_COOKIE)).contains("Max-Age=0");
            softly.assertThat(setCookie(actual, REFRESH_COOKIE)).contains("Max-Age=0");
            softly.assertThat(afterwards.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(jdbcClient.sql("SELECT count(*) FROM refresh_token").query(Long.class).single()).isZero();
        });
    }

    @Test
    void 본문의_리프레시_토큰으로도_로그아웃할_수_있다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/auth/logout")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"" + refreshToken + "\"}")));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(jdbcClient.sql("SELECT count(*) FROM refresh_token").query(Long.class).single()).isZero();
        });
    }

    @Test
    void 액세스_토큰이_만료됐어도_리프레시_쿠키가_있으면_갱신_가능으로_알린다() throws Exception {
        // given
        Clock longAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String expired = new AccessTokens(tokenProperties, longAgo).issue(memberId);

        // when
        JsonNode refreshable = session(ACCESS_COOKIE + "=" + expired + "; " + REFRESH_COOKIE + "=" + refreshToken);
        JsonNode anonymous = session("");
        JsonNode signedIn = session(ACCESS_COOKIE + "=" + accessTokens.issue(memberId));

        // then
        assertSoftly(softly -> {
            softly.assertThat(refreshable.path("authenticated").asBoolean()).isFalse();
            softly.assertThat(refreshable.path("refreshable").asBoolean()).isTrue();
            softly.assertThat(anonymous.path("refreshable").asBoolean()).isFalse();
            softly.assertThat(signedIn.path("authenticated").asBoolean()).isTrue();
            softly.assertThat(signedIn.path("member").path("nickname").asString()).isEqualTo("연어팬");
        });
    }

    @Test
    void 탈퇴한_회원의_토큰이면_로그인_상태를_비로그인으로_답한다() throws Exception {
        // given
        String accessToken = accessTokens.issue(memberId);
        jdbcClient.sql("DELETE FROM member").update();

        // when
        HttpResponse<String> actual = send(request("/api/auth/session")
            .header("Cookie", ACCESS_COOKIE + "=" + accessToken)
            .GET());

        // then
        JsonNode body = objectMapper.readTree(actual.body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(body.path("authenticated").asBoolean()).isFalse();
            softly.assertThat(body.has("member")).isFalse();
        });
    }

    private JsonNode session(String cookie) throws IOException, InterruptedException {
        HttpRequest.Builder builder = request("/api/auth/session");
        if (!cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        return objectMapper.readTree(send(builder.GET()).body());
    }

    private HttpResponse<String> refreshWithCookie(String token) throws IOException, InterruptedException {
        return send(request("/api/auth/refresh")
            .header("Cookie", cookies(REFRESH_COOKIE + "=" + token))
            .header("X-XSRF-TOKEN", csrfToken)
            .POST(HttpRequest.BodyPublishers.noBody()));
    }

    private String cookies(String authCookie) {
        return authCookie + "; " + CSRF_COOKIE + "=" + csrfToken;
    }

    private String csrfToken() throws IOException, InterruptedException {
        return cookieValue(send(request("/api/auth/session").GET()), CSRF_COOKIE);
    }

    private String code(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).path("code").asString();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private static String setCookie(HttpResponse<String> response, String name) {
        List<String> cookies = response.headers().allValues("Set-Cookie");
        return cookies.stream().filter(cookie -> cookie.startsWith(name + "=")).findFirst().orElse("");
    }

    private static String cookieValue(HttpResponse<String> response, String name) {
        String cookie = setCookie(response, name);
        return cookie.substring(name.length() + 1).split(";", 2)[0];
    }
}
