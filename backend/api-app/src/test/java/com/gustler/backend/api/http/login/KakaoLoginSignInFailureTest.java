package com.gustler.backend.api.http.login;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.gustler.backend.member.api.SignInWithSocialAccount;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 제공자 인증은 통과했지만 회원 처리가 실패하면 토큰을 내리지 않고 로그인된 상태로 남지 않는다. */
@LoginIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class KakaoLoginSignInFailureTest {
    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FakeKakaoServer kakao;

    @Autowired
    private SignInWithSocialAccount signIn;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void 상태를_비운다() {
        kakao.reset();
        jdbcClient.sql("DELETE FROM member").update();
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
    }

    @Test
    void 회원_처리가_실패하면_실패_지표와_민감값_없는_로그를_남긴다(CapturedOutput output) throws Exception {
        // given
        double failedBefore = failedLogins();
        doThrow(new IllegalStateException("비밀번호가 섞일 수 있는 DB 장애")).when(signIn).signIn(any());
        TestBrowser browser = new TestBrowser(port);
        String authorization = browser.get("/api/auth/kakao/start").headers().firstValue("Location").orElseThrow();
        String state = UriComponentsBuilder.fromUriString(authorization).build().getQueryParams().getFirst("state");
        String code = kakao.authorize(authorization, "kakao-subject-1");

        // when
        HttpResponse<String> callback = browser.get("/api/auth/kakao/callback?code=" + code + "&state=" + state);

        // then
        HttpResponse<String> me = browser.get("/api/me");
        assertSoftly(softly -> {
            softly.assertThat(callback.headers().firstValue("Location")).contains("/?login=failed");
            softly.assertThat(callback.headers().allValues("Set-Cookie"))
                .noneMatch(cookie -> cookie.startsWith("__Host-sb_at=") || cookie.startsWith("__Host-sb_rt="));
            softly.assertThat(me.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(jdbcClient.sql("SELECT count(*) FROM refresh_token").query(Long.class).single()).isZero();
            softly.assertThat(failedLogins() - failedBefore).isEqualTo(1);
            softly.assertThat(output.getAll())
                .contains("event=user_login status=FAILED provider=kakao")
                .doesNotContain("비밀번호가 섞일 수 있는");
        });
    }

    private double failedLogins() {
        var counter = meters.find("salmonbus.user.login")
            .tags("provider", "kakao", "result", "failed", "member", "unknown").counter();
        return counter == null ? 0 : counter.count();
    }
}
