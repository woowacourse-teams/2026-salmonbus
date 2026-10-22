package com.gustler.backend.api.http.login;

import static com.gustler.backend.api.http.login.LoginBrowser.header;
import static com.gustler.backend.api.http.login.LoginBrowser.location;
import static com.gustler.backend.api.http.login.LoginBrowser.stateOf;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.account.token.TokenProperties;
import com.gustler.backend.api.account.token.WithdrawalTickets;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 애플 회원이 애플로 다시 인증해 탈퇴하는 흐름을 실제 톰캣과 테스트용 애플 서버로 검증한다. 실제 애플은 부르지 않는다.
 *
 * <p>회원은 먼저 애플 로그인 흐름으로 로그인해 인증 쿠키를 가진 상태에서 시작한다. 탈퇴 시작은 CSRF 로 보호되는 POST 로
 * 티켓이 든 시작 주소를 받아 그 주소로 이동한다. 애플 토큰을 취소해야 회원을 지우고, 취소하지 못하면 회원과 인증 쿠키를 그대로
 * 둔다.
 */
@LoginIntegrationTest
class AppleWithdrawalFlowTest {
    private static final String SUBJECT = "001234.apple.member";
    private static final String OTHER_SUBJECT = "009999.apple.someone";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TokenProperties tokenProperties;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private FakeAppleServer apple;

    @BeforeEach
    void 상태를_비운다() {
        apple.reset();
        jdbcClient.sql("DELETE FROM member").update();
        jdbcClient.sql("DELETE FROM provider_unlink").update();
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
    }

    @Test
    void 같은_애플_계정으로_다시_인증하면_토큰을_취소하고_탈퇴한다() throws Exception {
        // given
        LoginBrowser browser = signedIn(SUBJECT);

        // when
        HttpResponse<String> started = startWithdrawal(browser, "/routes/123456789");
        HttpResponse<String> callback = reauthenticate(browser, location(started), SUBJECT);

        // then
        List<FakeAppleServer.RevokeRequest> revokes = apple.revokeRequests();
        HttpResponse<String> session = browser.get("/api/auth/session");
        assertSoftly(softly -> {
            softly.assertThat(started.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(location(started)).startsWith(apple.baseUrl() + "/auth/authorize");
            softly.assertThat(location(callback)).isEqualTo("/?withdrawn=1");
            softly.assertThat(header(callback, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(callback.headers().allValues("Set-Cookie"))
                .anyMatch(cookie -> cookie.startsWith("__Host-sb_at=;") && cookie.contains("Max-Age=0"))
                .anyMatch(cookie -> cookie.startsWith("__Host-sb_rt=;") && cookie.contains("Max-Age=0"));
            softly.assertThat(count("member")).isZero();
            softly.assertThat(count("refresh_token")).isZero();
            softly.assertThat(count("provider_unlink")).as("취소에 성공하면 남기지 않는다").isZero();
            softly.assertThat(revokes).hasSize(1);
            softly.assertThat(revokes.get(0).token()).isEqualTo(FakeAppleServer.ACCESS_TOKEN);
            softly.assertThat(revokes.get(0).tokenTypeHint()).isEqualTo("access_token");
            softly.assertThat(revokes.get(0).clientId()).isEqualTo(FakeAppleServer.CLIENT_ID);
            softly.assertThat(revokes.get(0).clientSecretValid()).isTrue();
            softly.assertThat(objectMapper.readTree(session.body()).path("authenticated").asBoolean()).isFalse();
        });
    }

    @Test
    void 다른_애플_계정으로_다시_인증하면_탈퇴하지도_가입시키지도_않는다() throws Exception {
        // given
        LoginBrowser browser = signedIn(SUBJECT);

        // when
        HttpResponse<String> started = startWithdrawal(browser, "/routes/123456789");
        HttpResponse<String> callback = reauthenticate(browser, location(started), OTHER_SUBJECT);

        // then
        assertSoftly(softly -> {
            softly.assertThat(location(callback)).isEqualTo("/routes/123456789?withdraw=failed");
            softly.assertThat(count("member")).isEqualTo(1);
            softly.assertThat(apple.revokeRequests()).isEmpty();
            softly.assertThat(callback.headers().allValues("Set-Cookie"))
                .noneMatch(cookie -> cookie.startsWith("__Host-sb_at=") || cookie.startsWith("__Host-sb_rt="));
        });
    }

    @Test
    void 애플이_토큰_취소에_실패하면_탈퇴하지_않고_로그인을_유지한다() throws Exception {
        // given
        LoginBrowser browser = signedIn(SUBJECT);
        apple.answerRevokeWith(HttpStatus.SERVICE_UNAVAILABLE.value());
        double revokeFailedBefore = revokeFailed();

        // when
        HttpResponse<String> started = startWithdrawal(browser, "/routes/123456789");
        HttpResponse<String> callback = reauthenticate(browser, location(started), SUBJECT);

        // then
        HttpResponse<String> session = browser.get("/api/auth/session");
        assertSoftly(softly -> {
            softly.assertThat(location(callback)).isEqualTo("/routes/123456789?withdraw=failed");
            softly.assertThat(header(callback, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(callback.headers().allValues("Set-Cookie"))
                .noneMatch(cookie -> cookie.startsWith("__Host-sb_at=") || cookie.startsWith("__Host-sb_rt="));
            softly.assertThat(apple.revokeRequests()).hasSize(1);
            softly.assertThat(count("member")).isEqualTo(1);
            softly.assertThat(count("refresh_token")).isEqualTo(1);
            softly.assertThat(count("provider_unlink")).isZero();
            softly.assertThat(revokeFailed() - revokeFailedBefore).isEqualTo(1);
            softly.assertThat(objectMapper.readTree(session.body()).path("authenticated").asBoolean()).isTrue();
        });
    }

    @Test
    void 애플_화면에서_취소하면_탈퇴하지_않고_withdraw_cancelled로_돌려보낸다() throws Exception {
        // given
        LoginBrowser browser = signedIn(SUBJECT);
        String authorization = location(startWithdrawal(browser, "/"));

        // when
        HttpResponse<String> callback = browser.get(
            "/api/auth/apple/callback?error=user_cancelled_authorize&state=" + stateOf(authorization));

        // then
        assertSoftly(softly -> {
            softly.assertThat(location(callback)).isEqualTo("/?withdraw=cancelled");
            softly.assertThat(count("member")).isEqualTo(1);
        });
    }

    @Test
    void 로그인하지_않았으면_탈퇴_티켓을_받을_수_없다() throws Exception {
        // when
        HttpResponse<String> actual = new LoginBrowser(port).postJson("/api/me/withdrawal/apple", "{}");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(objectMapper.readTree(actual.body()).path("code").asString())
                .isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void 탈퇴_티켓은_CSRF_토큰_없이_쿠키만으로는_받을_수_없다() throws Exception {
        // given
        LoginBrowser browser = signedIn(SUBJECT);

        // when
        HttpResponse<String> actual = browser.post("/api/me/withdrawal/apple");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(objectMapper.readTree(actual.body()).path("code").asString()).isEqualTo("CSRF_REJECTED");
        });
    }

    @Test
    void 돌아갈_경로에_특수문자가_있어도_시작_주소가_깨지지_않고_첫_화면으로_돌아간다() throws Exception {
        // given
        LoginBrowser browser = signedIn(SUBJECT);

        // when
        HttpResponse<String> started = startWithdrawal(browser, "/routes/1{x}&ticket=evil#frag");
        HttpResponse<String> callback = reauthenticate(browser, location(started), OTHER_SUBJECT);

        // then
        assertSoftly(softly -> {
            softly.assertThat(started.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(location(started)).startsWith(apple.baseUrl() + "/auth/authorize");
            softly.assertThat(location(callback)).isEqualTo("/?withdraw=failed");
        });
    }

    @Test
    void 다른_사이트가_인증_쿠키만으로_탈퇴를_시작시키면_애플로_보내지_않는다() throws Exception {
        // given
        LoginBrowser browser = signedIn(SUBJECT);

        // when
        HttpResponse<String> actual = browser.get("/api/auth/apple/start?intent=withdraw&returnTo=/routes/123456789");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            softly.assertThat(location(actual)).isEqualTo("/routes/123456789?withdraw=failed");
            softly.assertThat(count("SPRING_SESSION")).as("탈퇴 표시를 남길 임시 세션을 만들지 않는다").isZero();
            softly.assertThat(count("member")).isEqualTo(1);
        });
    }

    @Test
    void 위조했거나_만료된_티켓으로는_시작할_수_없다() throws Exception {
        // given
        LoginBrowser browser = signedIn(SUBJECT);
        String memberId = jdbcClient.sql("SELECT public_id::text FROM member").query(String.class).single();
        Clock tenMinutesAgo = Clock.fixed(Instant.now().minus(Duration.ofMinutes(10)), ZoneOffset.UTC);
        String expired = new WithdrawalTickets(tokenProperties, tenMinutesAgo).issue(UUID.fromString(memberId));
        String valid = ticketOf(browser);
        String[] parts = valid.split("\\.");
        String forged = parts[0] + "." + (Long.parseLong(parts[1]) + 1) + "." + parts[2];

        // when
        HttpResponse<String> withExpired = browser.get("/api/auth/apple/start?intent=withdraw&ticket=" + expired);
        HttpResponse<String> withForged = browser.get("/api/auth/apple/start?intent=withdraw&ticket=" + forged);

        // then
        assertSoftly(softly -> {
            softly.assertThat(location(withExpired)).isEqualTo("/?withdraw=failed");
            softly.assertThat(location(withForged)).isEqualTo("/?withdraw=failed");
            softly.assertThat(count("member")).isEqualTo(1);
        });
    }

    @Test
    void 탈퇴를_시작했다가_일반_로그인을_다시_시작하면_로그인으로_처리한다() throws Exception {
        // given
        LoginBrowser browser = signedIn(SUBJECT);
        startWithdrawal(browser, "/");

        // when
        HttpResponse<String> started = browser.get("/api/auth/apple/start?returnTo=/routes/123456789");
        HttpResponse<String> callback = reauthenticate(browser, location(started), SUBJECT);

        // then
        assertSoftly(softly -> {
            softly.assertThat(location(callback)).isEqualTo("/routes/123456789");
            softly.assertThat(count("member")).isEqualTo(1);
            softly.assertThat(apple.revokeRequests()).isEmpty();
        });
    }

    private HttpResponse<String> startWithdrawal(LoginBrowser browser, String returnTo)
        throws IOException, InterruptedException {
        HttpResponse<String> issued = browser.postJson(
            "/api/me/withdrawal/apple", "{\"returnTo\":\"" + returnTo + "\"}");
        return browser.get(objectMapper.readTree(issued.body()).path("startUrl").asString());
    }

    private String ticketOf(LoginBrowser browser) throws IOException, InterruptedException {
        HttpResponse<String> issued = browser.postJson("/api/me/withdrawal/apple", "{}");
        String startUrl = objectMapper.readTree(issued.body()).path("startUrl").asString();
        return LoginBrowser.queryOf(startUrl).getFirst("ticket");
    }

    private LoginBrowser signedIn(String subject) throws IOException, InterruptedException {
        LoginBrowser browser = new LoginBrowser(port);
        reauthenticate(browser, location(browser.get("/api/auth/apple/start")), subject);
        apple.reset();
        return browser;
    }

    private HttpResponse<String> reauthenticate(LoginBrowser browser, String authorization, String subject)
        throws IOException, InterruptedException {
        String code = apple.authorize(authorization, subject);
        return browser.get("/api/auth/apple/callback?code=" + code + "&state=" + stateOf(authorization));
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private double revokeFailed() {
        return meters.counter("auth.withdraw", "provider", "APPLE", "result", "revoke_failed").count();
    }
}
