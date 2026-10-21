package com.gustler.backend.api.http.login;

import static com.gustler.backend.api.http.login.LoginBrowser.location;
import static com.gustler.backend.api.http.login.LoginBrowser.queryOf;
import static com.gustler.backend.api.http.login.LoginBrowser.stateOf;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.PostgresTestContainer;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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
 * 카카오와 애플을 한 앱에 함께 등록해도 서로 영향 없이 동작하는지 확인한다.
 *
 * <p>제공자마다 다른 규칙(카카오만 PKCE, 애플만 {@code response_mode} 와 생성한 {@code client_secret})이
 * 서로의 요청으로 새지 않아야 한다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class KakaoAndAppleLoginTest {
    private static FakeKakaoServer kakao;
    private static FakeAppleServer apple;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void 제공자_서버를_띄운다() throws Exception {
        startServersIfAbsent();
    }

    @AfterAll
    static void 제공자_서버를_내린다() {
        kakao.close();
        apple.close();
    }

    @DynamicPropertySource
    static void 로그인_설정(DynamicPropertyRegistry registry) throws Exception {
        startServersIfAbsent();
        registry.add("auth.enabled", () -> "true");
        registry.add("auth.public-base-url", () -> "https://www.salmonbus.test");
        registry.add("auth.kakao.enabled", () -> "true");
        registry.add("auth.kakao.client-id", () -> FakeKakaoServer.CLIENT_ID);
        registry.add("auth.kakao.client-secret", () -> FakeKakaoServer.CLIENT_SECRET);
        registry.add("auth.kakao.issuer", kakao::baseUrl);
        registry.add("auth.kakao.authorization-uri", () -> kakao.baseUrl() + "/oauth/authorize");
        registry.add("auth.kakao.token-uri", () -> kakao.baseUrl() + "/oauth/token");
        registry.add("auth.kakao.jwk-set-uri", () -> kakao.baseUrl() + "/jwks.json");
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

    private static void startServersIfAbsent() throws Exception {
        if (kakao == null) {
            kakao = new FakeKakaoServer();
            apple = new FakeAppleServer();
        }
    }

    @BeforeEach
    void 상태를_비운다() {
        kakao.reset();
        apple.reset();
        jdbcClient.sql("DELETE FROM member").update();
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
    }

    @Test
    void 두_제공자로_각각_로그인하면_서로_다른_회원이_제공자별_규칙으로_만들어진다() throws Exception {
        // given
        LoginBrowser kakaoBrowser = new LoginBrowser(port);
        LoginBrowser appleBrowser = new LoginBrowser(port);
        String kakaoAuthorization = location(kakaoBrowser.get("/api/auth/kakao/start"));
        String appleAuthorization = location(appleBrowser.get("/api/auth/apple/start"));
        MultiValueMap<String, String> kakaoQuery = queryOf(kakaoAuthorization);
        MultiValueMap<String, String> appleQuery = queryOf(appleAuthorization);

        // when
        HttpResponse<String> kakaoCallback = kakaoBrowser.get("/api/auth/kakao/callback?code="
            + kakao.authorize(kakaoAuthorization, "kakao-subject") + "&state=" + stateOf(kakaoAuthorization));
        HttpResponse<String> appleCallback = appleBrowser.get("/api/auth/apple/callback?code="
            + apple.authorize(appleAuthorization, "apple-subject") + "&state=" + stateOf(appleAuthorization));

        // then
        JsonNode kakaoSession = objectMapper.readTree(kakaoBrowser.get("/api/auth/session").body());
        JsonNode appleSession = objectMapper.readTree(appleBrowser.get("/api/auth/session").body());
        FakeKakaoServer.TokenRequest kakaoToken = kakao.tokenRequests().get(0);
        FakeAppleServer.TokenRequest appleToken = apple.tokenRequests().get(0);
        assertSoftly(softly -> {
            softly.assertThat(kakaoAuthorization).startsWith(kakao.baseUrl());
            softly.assertThat(appleAuthorization).startsWith(apple.baseUrl());
            softly.assertThat(kakaoQuery.getFirst("code_challenge_method")).isEqualTo("S256");
            softly.assertThat(kakaoQuery.containsKey("response_mode")).isFalse();
            softly.assertThat(appleQuery.getFirst("response_mode")).isEqualTo("query");
            softly.assertThat(appleQuery.containsKey("code_challenge")).isFalse();
            softly.assertThat(kakaoCallback.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(appleCallback.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(kakaoSession.path("member").path("provider").asString()).isEqualTo("KAKAO");
            softly.assertThat(kakaoSession.path("member").path("nickname").asString()).matches(SignUpNicknames.PATTERN);
            softly.assertThat(appleSession.path("member").path("provider").asString()).isEqualTo("APPLE");
            softly.assertThat(appleSession.path("member").path("nickname").asString()).matches(SignUpNicknames.PATTERN);
            softly.assertThat(kakaoToken.form().get("client_secret")).isEqualTo(FakeKakaoServer.CLIENT_SECRET);
            softly.assertThat(kakaoToken.form().get("code_verifier")).isNotBlank();
            softly.assertThat(appleToken.form().get("client_secret")).hasSize(1);
            softly.assertThat(appleToken.form()).doesNotContainKey("code_verifier");
            softly.assertThat(jdbcClient.sql("SELECT provider FROM member_identity ORDER BY provider")
                .query(String.class).list()).isEqualTo(List.of("APPLE", "KAKAO"));
        });
    }
}
