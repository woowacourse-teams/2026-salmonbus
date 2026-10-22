package com.gustler.backend.api.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.TokenProperties;
import com.gustler.backend.member.api.ChangeNickname;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인한 회원의 정보 조회와 닉네임 변경, 모든 기기 로그아웃을 실제 톰캣으로 검증한다.
 *
 * <p>카카오 흐름은 {@code KakaoLoginFlowTest} 가 검증하므로 여기서는 액세스 토큰을 직접 만들어 쿠키로 보낸다.
 * 가입 닉네임은 무작위로 만들어지므로 가입한 뒤 알려진 닉네임으로 바꿔 두고 시작한다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "auth.enabled=true")
@Import(PostgresTestContainer.class)
class MeControllerTest {
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
    private AccessTokens accessTokens;

    @Autowired
    private TokenProperties tokenProperties;

    @Autowired
    private RefreshTokens refreshTokens;

    private UUID memberId;
    private String accessCookie;
    private String csrfToken;

    @BeforeEach
    void 회원과_액세스_토큰을_만든다() throws Exception {
        jdbcClient.sql("DELETE FROM member").update();
        memberId = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "kakao-1")).publicId();
        changeNickname.change(memberId, "연어팬");
        accessCookie = ACCESS_COOKIE + "=" + accessTokens.issue(memberId);
        csrfToken = csrfToken();
    }

    @Test
    void 내_정보를_조회한다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me").header("Cookie", accessCookie).GET());

        // then
        JsonNode body = objectMapper.readTree(actual.body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(body.path("id").asString()).isEqualTo(memberId.toString());
            softly.assertThat(body.path("nickname").asString()).isEqualTo("연어팬");
            softly.assertThat(body.path("provider").asString()).isEqualTo("KAKAO");
            softly.assertThat(body.path("createdAt").asString()).isNotBlank();
            softly.assertThat(body.has("email")).isFalse();
        });
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me").GET());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void Authorization_헤더로도_내_정보를_조회한다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Authorization", "Bearer " + accessTokens.issue(memberId))
            .GET());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(objectMapper.readTree(actual.body()).path("id").asString())
                .isEqualTo(memberId.toString());
        });
    }

    @Test
    void Authorization_헤더가_만료됐으면_유효한_액세스_쿠키가_있어도_401이다() throws Exception {
        // given
        Clock longAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String expired = new AccessTokens(tokenProperties, longAgo).issue(memberId);

        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Authorization", "Bearer " + expired)
            .header("Cookie", accessCookie)
            .GET());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void Authorization_헤더가_깨졌으면_유효한_액세스_쿠키가_있어도_CSRF_토큰_없이_보낸_변경은_401이다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Authorization", "Bearer not-a-token")
            .header("Cookie", accessCookie)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nickname\":\"새이름\"}")));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    @Test
    void 만료된_토큰이면_401이다() throws Exception {
        // given
        Clock longAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String expired = new AccessTokens(tokenProperties, longAgo).issue(memberId);

        // when
        HttpResponse<String> actual = send(request("/api/me").header("Cookie", ACCESS_COOKIE + "=" + expired).GET());

        // then
        assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void 다른_비밀로_서명한_토큰이면_401이다() throws Exception {
        // given
        TokenProperties other = new TokenProperties(
            "another-secret-key-another-secret-key-12345", "k1", Duration.ofMinutes(15));
        String forged = new AccessTokens(other, Clock.systemUTC()).issue(memberId);

        // when
        HttpResponse<String> actual = send(request("/api/me").header("Cookie", ACCESS_COOKIE + "=" + forged).GET());

        // then
        assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void 탈퇴한_회원의_토큰이_남아_있으면_401이다() throws Exception {
        // given
        jdbcClient.sql("DELETE FROM member").update();

        // when
        HttpResponse<String> actual = send(request("/api/me").header("Cookie", accessCookie).GET());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void 탈퇴한_회원의_토큰으로_닉네임을_바꾸려_하면_401이다() throws Exception {
        // given
        jdbcClient.sql("DELETE FROM member").update();

        // when
        HttpResponse<String> actual = patch("{\"nickname\":\"새이름\"}");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void 닉네임을_바꾸면_응답과_다음_조회에_반영된다() throws Exception {
        // when
        HttpResponse<String> actual = patch("{\"nickname\":\"  새이름  \"}");

        // then
        JsonNode body = objectMapper.readTree(actual.body());
        HttpResponse<String> lookup = send(request("/api/me").header("Cookie", accessCookie).GET());
        JsonNode afterwards = objectMapper.readTree(lookup.body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(body.path("nickname").asString()).isEqualTo("새이름");
            softly.assertThat(afterwards.path("nickname").asString()).isEqualTo("새이름");
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"nickname\":\"a\"}|NICKNAME_LENGTH",
        "{\"nickname\":\"가나다라마바사아자차카\"}|NICKNAME_LENGTH",
        "{\"nickname\":\"\"}|NICKNAME_LENGTH",
        "{}|NICKNAME_LENGTH",
        "{\"nickname\":null}|NICKNAME_LENGTH",
        "{\"nickname\":\"연어 버스\"}|NICKNAME_CHARACTERS",
        "{\"nickname\":\"ㄱㄴㄷ\"}|NICKNAME_CHARACTERS",
        "{\"nickname\":\"관리자1\"}|NICKNAME_RESERVED",
        "{\"nickname\":\"ADMIN1\"}|NICKNAME_RESERVED",
    })
    void 규칙을_어기면_400과_어긴_규칙의_코드로_답한다(String requestBody, String expected) throws Exception {
        // when
        HttpResponse<String> actual = patch(requestBody);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo(expected);
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    @Test
    void 다른_회원이_쓰는_닉네임이면_대소문자가_달라도_409다() throws Exception {
        // given
        UUID other = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "kakao-2")).publicId();
        changeNickname.change(other, "Salmon");

        // when
        HttpResponse<String> actual = patch("{\"nickname\":\"SALMON\"}");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
            softly.assertThat(code(actual)).isEqualTo("NICKNAME_TAKEN");
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    @Test
    void 자기_닉네임과_같은_값으로_바꾸는_것은_성공한다() throws Exception {
        // when
        HttpResponse<String> actual = patch("{\"nickname\":\"연어팬\"}");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    @Test
    void Authorization_헤더에_토큰_값이_없으면_액세스_쿠키로_내_정보를_조회한다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Authorization", "Bearer")
            .header("Cookie", accessCookie)
            .GET());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(objectMapper.readTree(actual.body()).path("id").asString())
                .isEqualTo(memberId.toString());
        });
    }

    @Test
    void Authorization_헤더에_토큰_값이_없으면_액세스_쿠키로_보낸_변경은_CSRF_토큰을_요구한다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Authorization", "Bearer")
            .header("Cookie", accessCookie)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nickname\":\"새이름\"}")));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo("CSRF_REJECTED");
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    @Test
    void CSRF_토큰이_없으면_닉네임을_바꿀_수_없다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Cookie", accessCookie)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nickname\":\"새이름\"}")));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo("CSRF_REJECTED");
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    @Test
    void 토큰이_없으면_CSRF_토큰이_있어도_401이다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Cookie", CSRF_COOKIE + "=" + csrfToken)
            .header("X-XSRF-TOKEN", csrfToken)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nickname\":\"새이름\"}")));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void 본문이_JSON이_아니면_400_INVALID_REQUEST다() throws Exception {
        // when
        HttpResponse<String> actual = patch("not json");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo("INVALID_REQUEST");
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    @Test
    void 모든_기기에서_로그아웃하면_두_기기의_리프레시_토큰이_모두_거절되고_인증_쿠키를_지운다() throws Exception {
        // given
        String phone = refreshTokens.issue(memberId).token();
        String laptop = refreshTokens.issue(memberId).token();

        // when
        HttpResponse<String> actual = logoutAllSessions(accessCookie + "; " + CSRF_COOKIE + "=" + csrfToken, csrfToken);
        HttpResponse<String> phoneRefresh = refresh(phone);
        HttpResponse<String> laptopRefresh = refresh(laptop);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(setCookie(actual, ACCESS_COOKIE)).contains("Max-Age=0");
            softly.assertThat(setCookie(actual, REFRESH_COOKIE)).contains("Max-Age=0");
            softly.assertThat(phoneRefresh.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(laptopRefresh.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(refreshTokenCount()).isZero();
        });
    }

    @Test
    void 쿠키로_인증하고_CSRF_토큰이_없으면_모든_기기에서_로그아웃할_수_없다() throws Exception {
        // given
        refreshTokens.issue(memberId);
        refreshTokens.issue(memberId);

        // when
        HttpResponse<String> actual = send(request("/api/me/sessions").header("Cookie", accessCookie).DELETE());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo("CSRF_REJECTED");
            softly.assertThat(refreshTokenCount()).isEqualTo(2);
        });
    }

    @Test
    void 토큰이_없으면_모든_기기에서_로그아웃할_수_없고_401이다() throws Exception {
        // given
        refreshTokens.issue(memberId);

        // when
        HttpResponse<String> actual = logoutAllSessions(CSRF_COOKIE + "=" + csrfToken, csrfToken);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
            softly.assertThat(refreshTokenCount()).isEqualTo(1);
        });
    }

    @Test
    void 탈퇴한_회원의_토큰으로_모든_기기에서_로그아웃하려_하면_401이다() throws Exception {
        // given
        jdbcClient.sql("DELETE FROM member").update();

        // when
        HttpResponse<String> actual = logoutAllSessions(accessCookie + "; " + CSRF_COOKIE + "=" + csrfToken, csrfToken);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
        });
    }

    private HttpResponse<String> logoutAllSessions(
        String cookies,
        String csrfHeader
    ) throws IOException, InterruptedException {
        return send(request("/api/me/sessions")
            .header("Cookie", cookies)
            .header("X-XSRF-TOKEN", csrfHeader)
            .DELETE());
    }

    private HttpResponse<String> refresh(String refreshToken) throws IOException, InterruptedException {
        return send(request("/api/auth/refresh")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"" + refreshToken + "\"}")));
    }

    private long refreshTokenCount() {
        return jdbcClient.sql("SELECT count(*) FROM refresh_token").query(Long.class).single();
    }

    private HttpResponse<String> patch(String body) throws IOException, InterruptedException {
        return send(request("/api/me")
            .header("Cookie", accessCookie + "; " + CSRF_COOKIE + "=" + csrfToken)
            .header("X-XSRF-TOKEN", csrfToken)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body)));
    }

    private String csrfToken() throws IOException, InterruptedException {
        HttpResponse<String> response = send(request("/api/auth/session").GET());
        return response.headers().allValues("Set-Cookie").stream()
            .filter(cookie -> cookie.startsWith(CSRF_COOKIE + "="))
            .map(cookie -> cookie.substring(CSRF_COOKIE.length() + 1).split(";", 2)[0])
            .findFirst()
            .orElseThrow();
    }

    private String nickname() {
        return jdbcClient.sql("SELECT nickname FROM member WHERE public_id = ?")
            .param(memberId)
            .query(String.class)
            .single();
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
}
