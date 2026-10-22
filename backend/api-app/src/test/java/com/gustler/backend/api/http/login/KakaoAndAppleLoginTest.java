package com.gustler.backend.api.http.login;

import static com.gustler.backend.api.http.login.LoginBrowser.location;
import static com.gustler.backend.api.http.login.LoginBrowser.queryOf;
import static com.gustler.backend.api.http.login.LoginBrowser.stateOf;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 카카오와 애플을 한 앱에 함께 등록해도 서로 영향 없이 동작하는지 확인한다.
 *
 * <p>제공자마다 다른 규칙(카카오만 PKCE, 애플만 {@code response_mode} 와 생성한 {@code client_secret})이
 * 서로의 요청으로 새지 않아야 한다.
 */
@LoginIntegrationTest
class KakaoAndAppleLoginTest {
    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FakeKakaoServer kakao;

    @Autowired
    private FakeAppleServer apple;

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
