package com.gustler.backend.api.http.login;

import static com.gustler.backend.api.http.login.LoginBrowser.header;
import static com.gustler.backend.api.http.login.LoginBrowser.location;
import static com.gustler.backend.api.http.login.LoginBrowser.queryOf;
import static com.gustler.backend.api.http.login.LoginBrowser.stateOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.PostgresTestContainer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 애플 로그인 흐름을 실제 톰캣과 테스트용 애플 서버로 끝까지 검증한다. 실제 애플은 부르지 않는다.
 *
 * <p>카카오 흐름과 같은 방식으로 브라우저를 흉내 낸다. 애플 화면에서 사용자가 동의한 뒤 콜백으로 돌아오는 단계는
 * 테스트가 인가 요청에서 읽은 {@code state}·{@code nonce} 로 직접 호출한다. 카카오는 꺼 두어 애플만 등록한
 * 컨텍스트에서 확인한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class AppleLoginFlowTest {
    private static final String SUBJECT = "001234.apple.subject";

    private static FakeAppleServer apple;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void 애플_서버를_띄운다() throws Exception {
        if (apple == null) {
            apple = new FakeAppleServer();
        }
    }

    @AfterAll
    static void 애플_서버를_내린다() {
        apple.close();
    }

    @DynamicPropertySource
    static void 로그인_설정(DynamicPropertyRegistry registry) throws Exception {
        if (apple == null) {
            apple = new FakeAppleServer();
        }
        registry.add("auth.enabled", () -> "true");
        registry.add("auth.public-base-url", () -> "https://www.salmonbus.test");
        registry.add("auth.apple.enabled", () -> "true");
        registry.add("auth.apple.client-id", () -> FakeAppleServer.CLIENT_ID);
        registry.add("auth.apple.team-id", () -> FakeAppleServer.TEAM_ID);
        registry.add("auth.apple.key-id", () -> FakeAppleServer.KEY_ID);
        registry.add("auth.apple.private-key-path", () -> apple.privateKeyFile().toString());
        registry.add("auth.apple.issuer", apple::baseUrl);
        registry.add("auth.apple.authorization-uri", () -> apple.baseUrl() + "/auth/authorize");
        registry.add("auth.apple.token-uri", () -> apple.baseUrl() + "/auth/token");
        registry.add("auth.apple.jwk-set-uri", () -> apple.baseUrl() + "/auth/keys");
    }

    @BeforeEach
    void 상태를_비운다() {
        apple.reset();
        jdbcClient.sql("DELETE FROM member").update();
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
    }

    @Test
    void 처음_로그인하면_회원이_자동_닉네임으로_만들어지고_돌아갈_경로로_보낸다() throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);
        HttpResponse<String> started = browser.get("/api/auth/apple/start?returnTo=/routes/123456789?direction=UP");
        String authorization = location(started);
        MultiValueMap<String, String> query = queryOf(authorization);

        // when
        String code = apple.authorize(authorization, SUBJECT);
        HttpResponse<String> callback = browser.get(callbackPath(authorization, code));

        // then
        JsonNode session = objectMapper.readTree(browser.get("/api/auth/session").body());
        assertSoftly(softly -> {
            softly.assertThat(started.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(authorization).startsWith(apple.baseUrl() + "/auth/authorize");
            softly.assertThat(query.getFirst("response_type")).isEqualTo("code");
            softly.assertThat(query.getFirst("client_id")).isEqualTo(FakeAppleServer.CLIENT_ID);
            softly.assertThat(query.getFirst("scope")).isEqualTo("openid");
            softly.assertThat(query.getFirst("redirect_uri"))
                .isEqualTo("https://www.salmonbus.test/api/auth/apple/callback");
            softly.assertThat(query.getFirst("response_mode")).isEqualTo("query");
            softly.assertThat(query.getFirst("state")).isNotBlank();
            softly.assertThat(query.getFirst("nonce")).isNotBlank();
            softly.assertThat(query.containsKey("code_challenge")).isFalse();
            softly.assertThat(query.containsKey("code_challenge_method")).isFalse();
            softly.assertThat(callback.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(location(callback)).isEqualTo("/routes/123456789?direction=UP");
            softly.assertThat(header(callback, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(session.path("authenticated").asBoolean()).isTrue();
            softly.assertThat(session.path("member").path("nickname").asString()).matches(SignUpNicknames.PATTERN);
            softly.assertThat(session.path("member").path("provider").asString()).isEqualTo("APPLE");
            softly.assertThat(memberCount()).isEqualTo(1);
            softly.assertThat(identityCount()).isEqualTo(1);
            softly.assertThat(jdbcClient.sql("SELECT provider FROM member_identity").query(String.class).single())
                .isEqualTo("APPLE");
            softly.assertThat(jdbcClient.sql("SELECT nickname FROM member").query(String.class).single())
                .matches(SignUpNicknames.PATTERN);
        });
    }

    @Test
    void 다시_로그인하면_같은_회원이다() throws Exception {
        // given
        String firstId = signIn();

        // when
        String secondId = signIn();

        // then
        assertSoftly(softly -> {
            softly.assertThat(secondId).isEqualTo(firstId);
            softly.assertThat(memberCount()).isEqualTo(1);
            softly.assertThat(identityCount()).isEqualTo(1);
        });
    }

    @Test
    void 토큰_요청의_client_secret은_테스트_키로_서명된_짧은_ES256_JWT이고_PKCE_검증값은_없다() throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);
        String authorization = location(browser.get("/api/auth/apple/start"));
        String code = apple.authorize(authorization, SUBJECT);

        // when
        browser.get(callbackPath(authorization, code));

        // then
        FakeAppleServer.TokenRequest request = apple.tokenRequests().get(0);
        SignedJWT secret = SignedJWT.parse(request.single("client_secret"));
        JWTClaimsSet claims = secret.getJWTClaimsSet();
        final boolean signatureVerified = verifies(secret);
        Duration validity = Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant());
        assertSoftly(softly -> {
            softly.assertThat(request.form().get("client_secret")).hasSize(1);
            softly.assertThat(request.form().get("client_id")).containsExactly(FakeAppleServer.CLIENT_ID);
            softly.assertThat(request.form().get("grant_type")).containsExactly("authorization_code");
            softly.assertThat(request.form().get("redirect_uri"))
                .containsExactly("https://www.salmonbus.test/api/auth/apple/callback");
            softly.assertThat(request.form()).doesNotContainKey("code_verifier");
            softly.assertThat(request.authorizationHeader()).isNull();
            softly.assertThat(secret.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
            softly.assertThat(secret.getHeader().getKeyID()).isEqualTo(FakeAppleServer.KEY_ID);
            softly.assertThat(signatureVerified).isTrue();
            softly.assertThat(claims.getIssuer()).isEqualTo(FakeAppleServer.TEAM_ID);
            softly.assertThat(claims.getSubject()).isEqualTo(FakeAppleServer.CLIENT_ID);
            softly.assertThat(claims.getAudience()).containsExactly("https://appleid.apple.com");
            softly.assertThat(validity).isEqualTo(Duration.ofMinutes(5));
            softly.assertThat(claims.getIssueTime().toInstant()).isBetween(
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        });
    }

    @Test
    void 로그인하면_임시_세션을_지우고_토큰_쿠키를_내리며_제공자_정보는_어디에도_남지_않는다() throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);
        String authorization = location(browser.get("/api/auth/apple/start"));
        final long beforeLogin = maxInactiveInterval();

        // when
        String code = apple.authorize(authorization, SUBJECT);
        HttpResponse<String> callback = browser.get(callbackPath(authorization, code));

        // then
        String publicId = jdbcClient.sql("SELECT public_id::text FROM member").query(String.class).single();
        String access = CookieHeaders.named(callback, "__Host-sb_at");
        String refresh = CookieHeaders.named(callback, "__Host-sb_rt");
        JsonNode claims = CookieHeaders.jwtPayload(CookieHeaders.valueOf(access), objectMapper);
        assertSoftly(softly -> {
            softly.assertThat(beforeLogin).isEqualTo(Duration.ofMinutes(10).toSeconds());
            softly.assertThat(sessionCount()).isZero();
            softly.assertThat(access).contains("HttpOnly", "Secure", "SameSite=Lax");
            softly.assertThat(refresh).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/;");
            softly.assertThat(claims.propertyNames()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp");
            softly.assertThat(claims.path("sub").asString()).isEqualTo(publicId);
        });
    }

    @Test
    void 애플_화면에서_취소하면_cancelled로_돌려보내고_임시_세션을_지운다(CapturedOutput output) throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);
        String authorization = location(browser.get("/api/auth/apple/start?returnTo=/routes/123456789"));
        String state = stateOf(authorization);

        // when
        HttpResponse<String> callback = browser.get(
            "/api/auth/apple/callback?error=user_cancelled_authorize&state=" + state);

        // then
        assertSoftly(softly -> {
            softly.assertThat(callback.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(location(callback)).isEqualTo("/routes/123456789?login=cancelled");
            softly.assertThat(memberCount()).isZero();
            softly.assertThat(sessionCount()).isZero();
            softly.assertThat(apple.tokenRequests()).isEmpty();
            softly.assertThat(output.getAll()).doesNotContain(state);
        });
    }

    @Test
    void 취소가_아닌_오류는_failed로_돌려보낸다() throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);
        String authorization = location(browser.get("/api/auth/apple/start"));

        // when
        HttpResponse<String> callback = browser.get(
            "/api/auth/apple/callback?error=invalid_request&state=" + stateOf(authorization));

        // then
        assertSoftly(softly -> {
            softly.assertThat(location(callback)).isEqualTo("/?login=failed");
            softly.assertThat(memberCount()).isZero();
        });
    }

    @Test
    void state가_다르면_failed로_돌려보내고_회원을_만들지_않는다() throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);
        String authorization = location(browser.get("/api/auth/apple/start"));
        String code = apple.authorize(authorization, SUBJECT);

        // when
        HttpResponse<String> callback = browser.get("/api/auth/apple/callback?code=" + code + "&state=forged-state");

        // then
        assertSoftly(softly -> {
            softly.assertThat(location(callback)).isEqualTo("/?login=failed");
            softly.assertThat(memberCount()).isZero();
            softly.assertThat(apple.tokenRequests()).isEmpty();
        });
    }

    @Test
    void 애플이_토큰_교환에_실패하면_failed다() throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);
        String authorization = location(browser.get("/api/auth/apple/start"));
        String code = apple.authorize(authorization, SUBJECT);
        apple.failTokenExchangeWith(HttpStatus.INTERNAL_SERVER_ERROR.value());

        // when
        HttpResponse<String> callback = browser.get(callbackPath(authorization, code));

        // then
        assertSoftly(softly -> {
            softly.assertThat(location(callback)).isEqualTo("/?login=failed");
            softly.assertThat(memberCount()).isZero();
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
        LoginBrowser browser = new LoginBrowser(port);
        String authorization = location(browser.get("/api/auth/apple/start"));
        String code = apple.authorize(authorization, claims -> {
            claims.subject(SUBJECT);
            mutation.accept(claims);
        }, otherKey);

        // when
        HttpResponse<String> callback = browser.get(callbackPath(authorization, code));

        // then
        assertSoftly(softly -> {
            softly.assertThat(apple.tokenRequests()).as(name).hasSize(1);
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
    void 성공과_실패_어느_쪽에도_코드_state_토큰_client_secret이_로그에_남지_않는다(CapturedOutput output) throws Exception {
        // given
        LoginBrowser success = new LoginBrowser(port);
        String successAuthorization = location(success.get("/api/auth/apple/start"));
        String successCode = apple.authorize(successAuthorization, SUBJECT);
        LoginBrowser failure = new LoginBrowser(port);
        String failureAuthorization = location(failure.get("/api/auth/apple/start"));
        String failureCode = apple.authorize(failureAuthorization, claims -> claims.subject(SUBJECT), true);

        // when
        success.get(callbackPath(successAuthorization, successCode));
        failure.get(callbackPath(failureAuthorization, failureCode));

        // then
        List<String> secrets = apple.tokenRequests().stream()
            .map(request -> request.single("client_secret"))
            .toList();
        assertSoftly(softly -> {
            softly.assertThat(secrets).hasSize(2).doesNotContainNull();
            softly.assertThat(output.getAll()).doesNotContain(
                successCode, failureCode, stateOf(successAuthorization), stateOf(failureAuthorization),
                FakeAppleServer.ACCESS_TOKEN, "fake-apple-refresh-token", SUBJECT);
            apple.issuedIdTokens().forEach(token -> softly.assertThat(output.getAll()).doesNotContain(token));
            secrets.forEach(secret -> softly.assertThat(output.getAll()).doesNotContain(secret));
        });
    }

    @Test
    void 켜지지_않은_카카오는_시작할_수_없다() throws Exception {
        // when
        HttpResponse<String> kakao = new LoginBrowser(port).get("/api/auth/kakao/start");

        // then
        assertThat(kakao.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    private String signIn() throws Exception {
        LoginBrowser browser = new LoginBrowser(port);
        String authorization = location(browser.get("/api/auth/apple/start"));
        browser.get(callbackPath(authorization, apple.authorize(authorization, SUBJECT)));
        return objectMapper.readTree(browser.get("/api/auth/session").body()).path("member").path("id").asString();
    }

    private static boolean verifies(SignedJWT secret) throws Exception {
        return secret.verify(new ECDSAVerifier(apple.clientSecretPublicKey()));
    }

    private static String callbackPath(String authorizationLocation, String code) {
        return "/api/auth/apple/callback?code=" + code + "&state=" + stateOf(authorizationLocation);
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
