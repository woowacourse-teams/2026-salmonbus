package com.gustler.backend.api.http.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.nimbusds.jwt.JWTClaimsSet;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 카카오 로그인 흐름을 실제 톰캣과 테스트용 카카오 서버로 끝까지 검증한다. 실제 카카오는 부르지 않는다.
 *
 * <p>브라우저를 흉내 내 쿠키를 직접 주고받는다. 제공자 화면에서 사용자가 동의한 뒤 콜백 주소로 돌아오는 단계는
 * 테스트가 인가 요청에서 읽은 {@code state}·{@code nonce} 로 직접 호출한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@LoginIntegrationTest
class KakaoLoginFlowTest {
    private static final String SESSION_COOKIE = "__Host-sb_session";
    private static final String SUBJECT = "kakao-subject-1";
    private static final int THIRTY_DAYS_SECONDS = 30 * 24 * 60 * 60;
    private static final int TEN_MINUTES_SECONDS = 10 * 60;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FakeKakaoServer kakao;

    @BeforeEach
    void 상태를_비운다() {
        kakao.reset();
        jdbcClient.sql("DELETE FROM member").update();
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
    }

    @Test
    void 처음_로그인하면_생성한_닉네임으로_회원이_만들어지고_돌아갈_경로로_보낸다() throws Exception {
        // given
        TestBrowser browser = browser();
        HttpResponse<String> started = browser.get("/api/auth/kakao/start?returnTo=/routes/123456789?direction=UP");
        String authorization = location(started);
        MultiValueMap<String, String> query = queryOf(authorization);

        // when
        String code = kakao.authorize(authorization, claims -> claims.subject(SUBJECT).claim("nickname", "연어팬"), false);
        HttpResponse<String> callback = browser.get(callbackPath(authorization, code));

        // then
        JsonNode session = objectMapper.readTree(browser.get("/api/auth/session").body());
        FakeKakaoServer.TokenRequest tokenRequest = kakao.tokenRequests().get(0);
        assertSoftly(softly -> {
            softly.assertThat(started.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(authorization).startsWith(kakao.baseUrl() + "/oauth/authorize");
            softly.assertThat(query.getFirst("response_type")).isEqualTo("code");
            softly.assertThat(query.getFirst("client_id")).isEqualTo(FakeKakaoServer.CLIENT_ID);
            softly.assertThat(query.getFirst("scope")).isEqualTo("openid");
            softly.assertThat(query.getFirst("redirect_uri"))
                .isEqualTo("https://www.salmonbus.test/api/auth/kakao/callback");
            softly.assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
            softly.assertThat(query.getFirst("state")).isNotBlank();
            softly.assertThat(query.getFirst("nonce")).isNotBlank();
            softly.assertThat(callback.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(location(callback)).isEqualTo("/routes/123456789?direction=UP");
            softly.assertThat(header(callback, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(session.path("authenticated").asBoolean()).isTrue();
            softly.assertThat(session.path("member").path("nickname").asString())
                .as("카카오가 닉네임 클레임을 보내도 쓰지 않는다").matches(SignUpNicknames.PATTERN);
            softly.assertThat(session.path("member").path("provider").asString()).isEqualTo("KAKAO");
            softly.assertThat(tokenRequest.form().get("client_secret")).isEqualTo(FakeKakaoServer.CLIENT_SECRET);
            softly.assertThat(tokenRequest.authorizationHeader()).isNull();
            softly.assertThat(memberCount()).isEqualTo(1);
            softly.assertThat(identityCount()).isEqualTo(1);
        });
    }

    @Test
    void 로그인하면_임시_세션을_지우고_토큰_쿠키를_내리며_제공자_정보는_어디에도_남지_않는다() throws Exception {
        // given
        TestBrowser browser = browser();
        String authorization = location(browser.get("/api/auth/kakao/start"));
        final long beforeLogin = maxInactiveInterval();

        // when
        String code = kakao.authorize(authorization, SUBJECT);
        HttpResponse<String> callback = browser.get(callbackPath(authorization, code));

        // then
        String publicId = jdbcClient.sql("SELECT public_id::text FROM member").query(String.class).single();
        String access = CookieHeaders.named(callback, "__Host-sb_at");
        String refresh = CookieHeaders.named(callback, "__Host-sb_rt");
        JsonNode claims = CookieHeaders.jwtPayload(CookieHeaders.valueOf(access), objectMapper);
        assertSoftly(softly -> {
            softly.assertThat(beforeLogin).isEqualTo(TEN_MINUTES_SECONDS);
            softly.assertThat(sessionCount()).as("임시 세션은 로그인과 함께 지운다").isZero();
            softly.assertThat(access).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/;");
            softly.assertThat(CookieHeaders.maxAgeOf(access)).isEqualTo(15 * 60);
            softly.assertThat(refresh).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/;");
            softly.assertThat(CookieHeaders.maxAgeOf(refresh))
                .isBetween(THIRTY_DAYS_SECONDS - 10L, (long) THIRTY_DAYS_SECONDS);
            softly.assertThat(claims.propertyNames()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp");
            softly.assertThat(claims.path("sub").asString()).isEqualTo(publicId);
            softly.assertThat(longOf("SELECT count(*) FROM refresh_token")).isEqualTo(1);
            softly.assertThat(longOf("SELECT generation FROM refresh_token"))
                .as("테이블에는 토큰 값이 없고 계통의 세대만 있다").isZero();
        });
    }

    @Test
    void 다시_로그인하면_같은_회원이고_닉네임을_바꾸지_않는다() throws Exception {
        // given
        String firstId = signIn();
        String firstNickname = nickname();

        // when
        String secondId = signIn();

        // then
        assertSoftly(softly -> {
            softly.assertThat(secondId).isEqualTo(firstId);
            softly.assertThat(memberCount()).isEqualTo(1);
            softly.assertThat(nickname()).isEqualTo(firstNickname);
        });
    }

    @Test
    void 로그아웃하면_다시_비로그인이다() throws Exception {
        // given
        TestBrowser browser = browser();
        String authorization = location(browser.get("/api/auth/kakao/start"));
        browser.get(callbackPath(authorization, kakao.authorize(authorization, SUBJECT)));
        browser.get("/api/auth/session");

        // when
        HttpResponse<String> logout = browser.post("/api/auth/logout");

        // then
        JsonNode session = objectMapper.readTree(browser.get("/api/auth/session").body());
        assertSoftly(softly -> {
            softly.assertThat(logout.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(session.path("authenticated").asBoolean()).isFalse();
            softly.assertThat(session.has("member")).isFalse();
            softly.assertThat(memberCount()).isEqualTo(1);
        });
    }

    @Test
    void 제공자_화면에서_취소하면_cancelled로_돌려보내고_임시_세션을_지운다(CapturedOutput output) throws Exception {
        // given
        TestBrowser browser = browser();
        String authorization = location(browser.get("/api/auth/kakao/start?returnTo=/routes/123456789"));
        String state = stateOf(authorization);

        // when
        HttpResponse<String> callback = browser.get("/api/auth/kakao/callback?error=access_denied&state=" + state);

        // then
        assertSoftly(softly -> {
            softly.assertThat(callback.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(location(callback)).isEqualTo("/routes/123456789?login=cancelled");
            softly.assertThat(memberCount()).isZero();
            softly.assertThat(sessionCount()).isZero();
            softly.assertThat(output.getAll()).doesNotContain(state);
        });
    }

    @Test
    void state가_다르면_failed로_돌려보내고_회원을_만들지_않는다() throws Exception {
        // given
        TestBrowser browser = browser();
        String authorization = location(browser.get("/api/auth/kakao/start"));
        String code = kakao.authorize(authorization, SUBJECT);

        // when
        HttpResponse<String> callback = browser.get("/api/auth/kakao/callback?code=" + code + "&state=forged-state");

        // then
        assertSoftly(softly -> {
            softly.assertThat(location(callback)).isEqualTo("/?login=failed");
            softly.assertThat(memberCount()).isZero();
            softly.assertThat(kakao.tokenRequests()).isEmpty();
        });
    }

    @Test
    void 시작하지_않은_브라우저의_콜백은_failed다() throws Exception {
        // given
        TestBrowser starter = browser();
        String authorization = location(starter.get("/api/auth/kakao/start"));
        String code = kakao.authorize(authorization, SUBJECT);
        TestBrowser stranger = browser();

        // when
        HttpResponse<String> callback = stranger.get(callbackPath(authorization, code));

        // then
        assertSoftly(softly -> {
            softly.assertThat(location(callback)).isEqualTo("/?login=failed");
            softly.assertThat(memberCount()).isZero();
        });
    }

    @Test
    void 카카오가_토큰_교환에_실패하면_failed다(CapturedOutput output) throws Exception {
        // given
        TestBrowser browser = browser();
        String authorization = location(browser.get("/api/auth/kakao/start"));
        String code = kakao.authorize(authorization, SUBJECT);
        kakao.failTokenExchangeWith(HttpStatus.INTERNAL_SERVER_ERROR.value());

        // when
        HttpResponse<String> callback = browser.get(callbackPath(authorization, code));

        // then
        assertSoftly(softly -> {
            softly.assertThat(location(callback)).isEqualTo("/?login=failed");
            softly.assertThat(memberCount()).isZero();
            softly.assertThat(output.getAll()).doesNotContain(code);
        });
    }

    @ParameterizedTest
    @MethodSource("위조된_ID_토큰")
    void 검증에_실패하는_ID_토큰으로는_로그인되지_않는다(
        String name,
        Consumer<JWTClaimsSet.Builder> mutation,
        boolean otherKey
    ) throws Exception {
        // given
        TestBrowser browser = browser();
        String authorization = location(browser.get("/api/auth/kakao/start"));
        String code = kakao.authorize(authorization, claims -> {
            claims.subject(SUBJECT);
            mutation.accept(claims);
        }, otherKey);

        // when
        HttpResponse<String> callback = browser.get(callbackPath(authorization, code));

        // then
        assertSoftly(softly -> {
            softly.assertThat(location(callback)).as(name).isEqualTo("/?login=failed");
            softly.assertThat(memberCount()).as(name).isZero();
        });
    }

    static Stream<Arguments> 위조된_ID_토큰() {
        return Stream.of(
            args("nonce 불일치", claims -> claims.claim("nonce", "forged-nonce"), false),
            args("다른 클라이언트용", claims -> claims.audience("other-client"), false),
            args("다른 발급자", claims -> claims.issuer("https://evil.example"), false),
            args("만료", claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(3600))), false),
            args("알 수 없는 서명 키", claims -> { }, true),
            args("subject 없음", claims -> claims.subject(null), false));
    }

    private static Arguments args(String name, Consumer<JWTClaimsSet.Builder> mutation, boolean otherKey) {
        return Arguments.of(name, mutation, otherKey);
    }

    @Test
    void 이미_로그인한_브라우저가_다시_시작하고_실패_콜백을_받아도_로그아웃되지_않는다() throws Exception {
        // given
        TestBrowser browser = browser();
        String authorization = location(browser.get("/api/auth/kakao/start"));
        browser.get(callbackPath(authorization, kakao.authorize(authorization, SUBJECT)));

        // when
        HttpResponse<String> restarted = browser.get("/api/auth/kakao/start");
        HttpResponse<String> forged = browser.get("/api/auth/kakao/callback?error=access_denied&state=forged");

        // then
        JsonNode session = objectMapper.readTree(browser.get("/api/auth/session").body());
        assertSoftly(softly -> {
            softly.assertThat(restarted.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(location(forged)).isEqualTo("/?login=failed");
            softly.assertThat(session.path("authenticated").asBoolean()).isTrue();
        });
    }

    @Test
    void 허용하지_않은_returnTo는_첫_화면으로_돌아간다() throws Exception {
        // given
        TestBrowser browser = browser();
        String authorization = location(browser.get("/api/auth/kakao/start?returnTo=//evil.com"));

        // when
        String code = kakao.authorize(authorization, SUBJECT);
        HttpResponse<String> callback = browser.get(callbackPath(authorization, code));

        // then
        assertThat(location(callback)).isEqualTo("/");
    }

    @Test
    void 기본_로그인_화면은_없다() throws Exception {
        // when
        HttpResponse<String> loginPage = browser().get("/login");

        // then
        assertThat(loginPage.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    private TestBrowser browser() {
        return new TestBrowser(port);
    }

    private String signIn() throws Exception {
        TestBrowser browser = browser();
        String authorization = location(browser.get("/api/auth/kakao/start"));
        browser.get(callbackPath(authorization, kakao.authorize(authorization, SUBJECT)));
        return objectMapper.readTree(browser.get("/api/auth/session").body()).path("member").path("id").asString();
    }

    private static String callbackPath(String authorizationLocation, String code) {
        return "/api/auth/kakao/callback?code=" + code + "&state=" + stateOf(authorizationLocation);
    }

    private static String stateOf(String authorizationLocation) {
        return queryOf(authorizationLocation).getFirst("state");
    }

    private static MultiValueMap<String, String> queryOf(String authorizationLocation) {
        return UriComponentsBuilder.fromUriString(authorizationLocation).build().getQueryParams();
    }

    private static String location(HttpResponse<String> response) {
        return header(response, "Location");
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private String nickname() {
        return jdbcClient.sql("SELECT nickname FROM member").query(String.class).single();
    }

    private long longOf(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private long memberCount() {
        return jdbcClient.sql("SELECT count(*) FROM member").query(Long.class).single();
    }

    private long identityCount() {
        return jdbcClient.sql("SELECT count(*) FROM member_identity").query(Long.class).single();
    }

    private long sessionCount() {
        return jdbcClient.sql("SELECT count(*) FROM SPRING_SESSION").query(Long.class).single();
    }

    private long maxInactiveInterval() {
        return jdbcClient.sql("SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION").query(Long.class).single();
    }
}
