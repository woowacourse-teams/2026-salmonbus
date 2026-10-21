package com.gustler.backend.api.http.login;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.support.PostgresTestContainer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 제공자 인증은 통과했지만 회원 처리가 실패하면 로그인된 상태로 남지 않는다.
 *
 * <p>세션 ID 교체가 DB에 반영되기 전에 세션을 무효화하면 이전 ID의 로그인 전 세션 행이 만료까지 남는다.
 * 그 행에는 로그인 정보가 없으므로 로그인 컨텍스트가 어떤 세션에도 없는지를 확인한다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class KakaoLoginSignInFailureTest {
    private static FakeKakaoServer kakao;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private SignInWithSocialAccount signIn;

    @BeforeAll
    static void 카카오_서버를_띄운다() throws Exception {
        kakao = new FakeKakaoServer();
    }

    @AfterAll
    static void 카카오_서버를_내린다() {
        kakao.close();
    }

    @DynamicPropertySource
    static void 로그인_설정(DynamicPropertyRegistry registry) throws Exception {
        if (kakao == null) {
            kakao = new FakeKakaoServer();
        }
        registry.add("auth.enabled", () -> "true");
        registry.add("auth.public-base-url", () -> "https://www.salmonbus.test");
        registry.add("auth.kakao.enabled", () -> "true");
        registry.add("auth.kakao.client-id", () -> FakeKakaoServer.CLIENT_ID);
        registry.add("auth.kakao.client-secret", () -> FakeKakaoServer.CLIENT_SECRET);
        registry.add("auth.kakao.issuer", kakao::baseUrl);
        registry.add("auth.kakao.authorization-uri", () -> kakao.baseUrl() + "/oauth/authorize");
        registry.add("auth.kakao.token-uri", () -> kakao.baseUrl() + "/oauth/token");
        registry.add("auth.kakao.jwk-set-uri", () -> kakao.baseUrl() + "/jwks.json");
    }

    @Test
    void 회원_처리가_실패하면_failed로_돌려보내고_로그인된_세션을_남기지_않는다() throws Exception {
        // given
        when(signIn.signIn(any())).thenThrow(new IllegalStateException("DB 장애"));
        TestBrowser browser = new TestBrowser(port);
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
        String authorization = browser.get("/api/auth/kakao/start").headers().firstValue("Location").orElseThrow();
        String state = UriComponentsBuilder.fromUriString(authorization).build().getQueryParams().getFirst("state");
        String code = kakao.authorize(authorization, "kakao-subject-1", "연어팬");

        // when
        HttpResponse<String> callback = browser.get("/api/auth/kakao/callback?code=" + code + "&state=" + state);

        // then
        HttpResponse<String> me = browser.get("/api/me");
        final long signedInSessions = jdbcClient
            .sql("SELECT count(*) FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME = 'SPRING_SECURITY_CONTEXT'")
            .query(Long.class).single();
        assertSoftly(softly -> {
            softly.assertThat(callback.headers().firstValue("Location")).contains("/?login=failed");
            softly.assertThat(me.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(signedInSessions).isZero();
        });
    }
}
