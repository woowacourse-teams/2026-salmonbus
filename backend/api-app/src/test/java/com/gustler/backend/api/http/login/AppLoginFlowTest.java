package com.gustler.backend.api.http.login;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.http.login.AppIdTokenVerifier.NonceRule;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SignedInMember;
import com.gustler.backend.member.api.SocialIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 앱 네이티브 로그인(nonce 발급 → SDK ID 토큰 → 로그인)을 실제 톰캣과 테스트용 제공자 서버로 검증한다.
 *
 * <p>앱 SDK 가 받는 ID 토큰은 테스트용 서버가 같은 공개키로 서명해 만든다. 웹 회원과 같은 회원인지는 웹 로그인이 쓰는
 * 회원 처리로 먼저 가입시킨 뒤 회원 수와 공개 ID 로 본다.
 */
@LoginIntegrationTest
class AppLoginFlowTest {
    private static final String APPLE_SUBJECT = "001234.apple-app-subject.0001";
    private static final String KAKAO_SUBJECT = "4100000001";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FakeAppleServer apple;

    @Autowired
    private FakeKakaoServer kakao;

    @Autowired
    private SignInWithSocialAccount signIn;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void 상태를_비운다() {
        apple.reset();
        kakao.reset();
        jdbcClient.sql("DELETE FROM member").update();
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
    }

    @Test
    void 애플_앱_로그인은_같은_애플_계정의_웹_회원으로_로그인하고_인증_쿠키를_내린다() throws Exception {
        // given
        SignedInMember webMember = signIn.signIn(new SocialIdentity(LoginProvider.APPLE, APPLE_SUBJECT));
        double loginsBefore = login("apple", "success", "existing");
        LoginBrowser browser = new LoginBrowser(port);
        String nonce = nonceOf(browser.postJson("/api/auth/apple/native/nonce", "{}"));
        String idToken = apple.appIdToken(APPLE_SUBJECT, NonceRule.SHA256_HEX.expectedClaim(nonce), claims -> {
        }, false);

        // when
        HttpResponse<String> actual = browser.postJson("/api/auth/apple/native", body(idToken));

        // then
        JsonNode me = objectMapper.readTree(browser.get("/api/me").body());
        List<String> setCookies = actual.headers().allValues("Set-Cookie");
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(LoginBrowser.header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(setCookies).anyMatch(cookie -> cookie.startsWith("__Host-sb_at="));
            softly.assertThat(setCookies).anyMatch(cookie -> cookie.startsWith("__Host-sb_rt="));
            softly.assertThat(me.path("id").asString()).isEqualTo(webMember.publicId().toString());
            softly.assertThat(me.path("provider").asString()).isEqualTo("APPLE");
            softly.assertThat(memberCount()).isEqualTo(1);
            softly.assertThat(login("apple", "success", "existing") - loginsBefore).isEqualTo(1);
        });
    }

    @Test
    void 카카오_앱_로그인은_nonce를_그대로_쓰고_같은_카카오_계정의_웹_회원으로_로그인한다() throws Exception {
        // given
        SignedInMember webMember = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, KAKAO_SUBJECT));
        LoginBrowser browser = new LoginBrowser(port);
        String nonce = nonceOf(browser.postJson("/api/auth/kakao/native/nonce", "{}"));
        String idToken = kakao.appIdToken(KAKAO_SUBJECT, nonce, claims -> {
        }, false);

        // when
        HttpResponse<String> actual = browser.postJson("/api/auth/kakao/native", body(idToken));

        // then
        JsonNode me = objectMapper.readTree(browser.get("/api/me").body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(me.path("id").asString()).isEqualTo(webMember.publicId().toString());
            softly.assertThat(me.path("provider").asString()).isEqualTo("KAKAO");
            softly.assertThat(memberCount()).isEqualTo(1);
        });
    }

    @Test
    void 처음_보는_계정이면_가입시키고_기본_즐겨찾기_그룹을_만든다() throws Exception {
        // given
        double loginsBefore = login("kakao", "success", "new");
        LoginBrowser browser = new LoginBrowser(port);
        String nonce = nonceOf(browser.postJson("/api/auth/kakao/native/nonce", "{}"));
        long sessionsAfterNonce = sessionCount();

        // when
        HttpResponse<String> actual = browser.postJson("/api/auth/kakao/native",
            body(kakao.appIdToken(KAKAO_SUBJECT, nonce, claims -> {
            }, false)));

        long sessionsAfterLogin = sessionCount();

        // then
        JsonNode session = objectMapper.readTree(browser.get("/api/auth/session").body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(session.path("authenticated").asBoolean()).isTrue();
            softly.assertThat(session.path("member").path("nickname").asString()).matches(SignUpNicknames.PATTERN);
            softly.assertThat(memberCount()).isEqualTo(1);
            softly.assertThat(favoriteGroupCount()).isEqualTo(1);
            softly.assertThat(sessionsAfterNonce).isEqualTo(1);
            softly.assertThat(sessionsAfterLogin).isZero();
            softly.assertThat(login("kakao", "success", "new") - loginsBefore).isEqualTo(1);
        });
    }

    @Test
    void 한_번_쓴_nonce로는_다시_로그인할_수_없다() throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);
        String nonce = nonceOf(browser.postJson("/api/auth/kakao/native/nonce", "{}"));
        String idToken = kakao.appIdToken(KAKAO_SUBJECT, nonce, claims -> {
        }, false);
        HttpResponse<String> first = browser.postJson("/api/auth/kakao/native", body(idToken));

        // when
        HttpResponse<String> replay = new LoginBrowser(port).postJson("/api/auth/kakao/native", body(idToken));
        HttpResponse<String> sameBrowser = browser.postJson("/api/auth/kakao/native", body(idToken));

        // then
        assertSoftly(softly -> {
            softly.assertThat(first.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(replay.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(codeOf(replay)).isEqualTo("UNAUTHENTICATED");
            softly.assertThat(sameBrowser.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        });
    }

    @Test
    void 틀린_토큰으로_실패해도_nonce는_다시_쓸_수_없다() throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);
        String nonce = nonceOf(browser.postJson("/api/auth/kakao/native/nonce", "{}"));
        String forged = kakao.appIdToken(KAKAO_SUBJECT, nonce, claims -> {
        }, true);
        String valid = kakao.appIdToken(KAKAO_SUBJECT, nonce, claims -> {
        }, false);

        // when
        HttpResponse<String> rejected = browser.postJson("/api/auth/kakao/native", body(forged));
        HttpResponse<String> retried = browser.postJson("/api/auth/kakao/native", body(valid));

        // then
        assertSoftly(softly -> {
            softly.assertThat(rejected.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(rejected.headers().allValues("Set-Cookie"))
                .noneMatch(cookie -> cookie.startsWith("__Host-sb_at="));
            softly.assertThat(retried.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(memberCount()).isZero();
        });
    }

    @Test
    void 웹용_토큰으로_앱_로그인하면_거절하고_회원을_만들지_않는다() throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);
        String nonce = nonceOf(browser.postJson("/api/auth/apple/native/nonce", "{}"));
        String webToken = apple.appIdToken(APPLE_SUBJECT, NonceRule.SHA256_HEX.expectedClaim(nonce),
            claims -> claims.audience(FakeAppleServer.CLIENT_ID), false);

        // when
        HttpResponse<String> actual = browser.postJson("/api/auth/apple/native", body(webToken));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(memberCount()).isZero();
        });
    }

    @Test
    void CSRF_헤더가_없으면_nonce도_로그인도_거절한다() throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);
        browser.get("/api/auth/session");

        // when
        HttpResponse<String> nonce = browser.postJsonWithoutCsrf("/api/auth/kakao/native/nonce", "{}");
        HttpResponse<String> login = browser.postJsonWithoutCsrf("/api/auth/kakao/native", body("token"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(nonce.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(codeOf(nonce)).isEqualTo("CSRF_REJECTED");
            softly.assertThat(login.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(codeOf(login)).isEqualTo("CSRF_REJECTED");
        });
    }

    @Test
    void 사이트가_아닌_출처에서_온_요청은_거절하고_같은_출처는_받는다() throws Exception {
        // given
        LoginBrowser browser = new LoginBrowser(port);

        // when
        HttpResponse<String> foreign = browser.postJsonFrom(
            "https://evil.example", "/api/auth/kakao/native/nonce", "{}");
        HttpResponse<String> site = browser.postJsonFrom(
            "https://www.salmonbus.test", "/api/auth/kakao/native/nonce", "{}");

        // then
        assertSoftly(softly -> {
            softly.assertThat(foreign.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(codeOf(foreign)).isEqualTo("CSRF_REJECTED");
            softly.assertThat(site.statusCode()).isEqualTo(HttpStatus.OK.value());
        });
    }

    private String nonceOf(HttpResponse<String> response) {
        if (response.statusCode() != HttpStatus.OK.value()) {
            throw new IllegalStateException("nonce 를 받지 못했다. status=" + response.statusCode());
        }
        return objectMapper.readTree(response.body()).path("nonce").asString();
    }

    private String codeOf(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).path("code").asString();
    }

    private String body(String idToken) {
        return "{\"idToken\":\"" + idToken + "\"}";
    }

    private long memberCount() {
        return jdbcClient.sql("SELECT count(*) FROM member").query(Long.class).single();
    }

    private long favoriteGroupCount() {
        return jdbcClient.sql("SELECT count(*) FROM favorite_group").query(Long.class).single();
    }

    private long sessionCount() {
        return jdbcClient.sql("SELECT count(*) FROM SPRING_SESSION").query(Long.class).single();
    }

    private double login(String provider, String result, String member) {
        var counter = meters.find("salmonbus.user.login")
            .tags("provider", provider, "result", result, "member", member).counter();
        return counter == null ? 0 : counter.count();
    }
}
