package com.gustler.backend.api.http.login;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.PostgresTestContainer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/**
 * 카카오만 켜면 켜지 않은 애플의 시작 경로는 없는 경로로 답한다.
 *
 * <p>제공자가 하나라도 켜지면 로그인 시작 필터가 붙으므로, 켜진 제공자가 없는 앱과는 다른 경로를 지난다. 시작 경로만
 * 보므로 카카오 주소는 쓰이지 않는다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "auth.enabled=true",
    "auth.kakao.enabled=true",
    "auth.kakao.client-id=" + FakeKakaoServer.CLIENT_ID,
    "auth.kakao.client-secret=" + FakeKakaoServer.CLIENT_SECRET,
})
@Import(PostgresTestContainer.class)
class KakaoOnlyLoginTest {
    @LocalServerPort
    private int port;

    @Test
    void 켜지지_않은_애플은_로그인도_탈퇴도_시작할_수_없다() throws Exception {
        // when
        HttpResponse<String> apple = new TestBrowser(port).get("/api/auth/apple/start");
        HttpResponse<String> appleWithdrawal = new TestBrowser(port).get("/api/auth/apple/start?intent=withdraw");

        // then
        assertSoftly(softly -> {
            softly.assertThat(apple.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
            softly.assertThat(appleWithdrawal.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        });
    }
}
