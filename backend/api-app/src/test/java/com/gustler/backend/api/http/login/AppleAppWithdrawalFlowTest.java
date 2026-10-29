package com.gustler.backend.api.http.login;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.http.login.AppIdTokenVerifier.NonceRule;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 앱 애플 탈퇴(다시 인증한 인가 코드 → 앱 client_id 로 교환 → 계정 대조 → 애플 토큰 취소 → 회원 삭제)를 검증한다.
 *
 * <p>회원은 먼저 앱 애플 로그인으로 로그인한다. 다시 인증한 코드는 테스트용 애플 서버가 앱 {@code client_id} 전용으로 만든다.
 */
@LoginIntegrationTest
class AppleAppWithdrawalFlowTest {
    private static final String SUBJECT = "001234.apple.app.member";
    private static final String OTHER_SUBJECT = "009999.apple.someone";
    private static final String PATH = "/api/me/withdrawal/apple/native";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FakeAppleServer apple;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void 상태를_비운다() {
        apple.reset();
        jdbcClient.sql("DELETE FROM member").update();
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
    }

    @Test
    void 다시_인증한_코드로_앱_client_id로_교환하고_애플_토큰을_취소한_뒤_탈퇴한다() throws Exception {
        // given
        LoginBrowser browser = signedInWithApple(SUBJECT);
        double doneBefore = withdrawals("done");

        // when
        HttpResponse<String> actual = browser.postJson(PATH, code(apple.appAuthorizationCode(SUBJECT)));

        // then
        FakeAppleServer.TokenRequest exchange = apple.tokenRequests().get(0);
        List<FakeAppleServer.RevokeRequest> revokes = apple.revokeRequests();
        List<String> setCookies = actual.headers().allValues("Set-Cookie");
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(LoginBrowser.header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(setCookies).anyMatch(cookie -> cookie.startsWith("__Host-sb_at=;")
                && cookie.contains("Max-Age=0"));
            softly.assertThat(exchange.single("client_id")).isEqualTo(FakeAppleServer.APP_CLIENT_ID);
            softly.assertThat(exchange.single("grant_type")).isEqualTo("authorization_code");
            softly.assertThat(exchange.single("redirect_uri")).isNull();
            softly.assertThat(revokes).hasSize(1);
            softly.assertThat(revokes.get(0).clientId()).isEqualTo(FakeAppleServer.APP_CLIENT_ID);
            softly.assertThat(revokes.get(0).clientSecretValid()).isTrue();
            softly.assertThat(revokes.get(0).token()).isEqualTo(FakeAppleServer.ACCESS_TOKEN);
            softly.assertThat(count("member")).isZero();
            softly.assertThat(withdrawals("done") - doneBefore).isEqualTo(1);
        });
    }

    @Test
    void 다른_애플_계정으로_다시_인증하면_취소하지_않고_회원을_남긴다() throws Exception {
        // given
        LoginBrowser browser = signedInWithApple(SUBJECT);

        // when
        HttpResponse<String> actual = browser.postJson(PATH, code(apple.appAuthorizationCode(OTHER_SUBJECT)));

        // then
        final int profileStatus = browser.get("/api/me").statusCode();
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
            softly.assertThat(codeOf(actual)).isEqualTo("REAUTH_REQUIRED");
            softly.assertThat(apple.revokeRequests()).isEmpty();
            softly.assertThat(count("member")).isEqualTo(1);
            softly.assertThat(profileStatus).isEqualTo(HttpStatus.OK.value());
        });
    }

    @Test
    void 애플_토큰을_취소하지_못하면_회원과_로그인을_남긴다() throws Exception {
        // given
        LoginBrowser browser = signedInWithApple(SUBJECT);
        apple.answerRevokeWith(HttpStatus.SERVICE_UNAVAILABLE.value());

        // when
        HttpResponse<String> actual = browser.postJson(PATH, code(apple.appAuthorizationCode(SUBJECT)));

        // then
        final int profileStatus = browser.get("/api/me").statusCode();
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
            softly.assertThat(codeOf(actual)).isEqualTo("REAUTH_REQUIRED");
            softly.assertThat(count("member")).isEqualTo(1);
            softly.assertThat(profileStatus).isEqualTo(HttpStatus.OK.value());
        });
    }

    @Test
    void 교환한_ID_토큰의_서명이나_대상이_틀리면_회원을_남긴다() throws Exception {
        // given
        LoginBrowser browser = signedInWithApple(SUBJECT);
        String forged = apple.appAuthorizationCode(claims -> claims.subject(SUBJECT), true);
        String webAudience = apple.appAuthorizationCode(
            claims -> claims.subject(SUBJECT).audience(FakeAppleServer.CLIENT_ID), false);

        // when
        HttpResponse<String> forgedResult = browser.postJson(PATH, code(forged));
        HttpResponse<String> webAudienceResult = browser.postJson(PATH, code(webAudience));

        // then
        assertSoftly(softly -> {
            softly.assertThat(forgedResult.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
            softly.assertThat(webAudienceResult.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
            softly.assertThat(apple.revokeRequests()).isEmpty();
            softly.assertThat(count("member")).isEqualTo(1);
        });
    }

    @Test
    void 로그인하지_않았거나_CSRF_토큰이_없으면_거절한다() throws Exception {
        // given
        LoginBrowser signedIn = signedInWithApple(SUBJECT);

        // when
        HttpResponse<String> anonymous = new LoginBrowser(port).postJson(PATH, code("any"));
        HttpResponse<String> withoutCsrf = signedIn.postJsonWithoutCsrf(PATH, code("any"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(anonymous.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(withoutCsrf.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(codeOf(withoutCsrf)).isEqualTo("CSRF_REJECTED");
            softly.assertThat(apple.tokenRequests()).isEmpty();
            softly.assertThat(count("member")).isEqualTo(1);
        });
    }

    private LoginBrowser signedInWithApple(String subject) throws Exception {
        LoginBrowser browser = new LoginBrowser(port);
        String nonce = nonceOf(browser.postJson("/api/auth/apple/native/nonce", "{}"));
        String idToken = apple.appIdToken(subject, NonceRule.SHA256_HEX.expectedClaim(nonce), claims -> {
        }, false);
        expectNoContent(browser.postJson("/api/auth/apple/native", "{\"idToken\":\"" + idToken + "\"}"));
        return browser;
    }

    private static void expectNoContent(HttpResponse<String> response) {
        if (response.statusCode() != HttpStatus.NO_CONTENT.value()) {
            throw new IllegalStateException("앱 로그인에 실패했다. status=" + response.statusCode());
        }
    }

    private String nonceOf(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).path("nonce").asString();
    }

    private String codeOf(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).path("code").asString();
    }

    private static String code(String authorizationCode) {
        return "{\"authorizationCode\":\"" + authorizationCode + "\"}";
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private double withdrawals(String result) {
        var counter = meters.find("auth.withdraw").tags("provider", "APPLE", "result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
