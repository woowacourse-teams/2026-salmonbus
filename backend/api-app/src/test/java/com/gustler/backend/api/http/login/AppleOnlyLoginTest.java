package com.gustler.backend.api.http.login;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.PostgresTestContainer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/**
 * 애플만 켜면 켜지 않은 카카오의 시작 경로는 없는 경로로 답한다.
 *
 * <p>애플 개인 키 파일과 주소는 {@link FakeLoginProviders} 가 준다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "auth.enabled=true",
    "auth.public-base-url=https://www.salmonbus.test",
    "auth.apple.enabled=true",
    "auth.apple.client-id=" + FakeAppleServer.CLIENT_ID,
    "auth.apple.team-id=" + FakeAppleServer.TEAM_ID,
    "auth.apple.key-id=" + FakeAppleServer.KEY_ID,
})
@Import({PostgresTestContainer.class, FakeLoginProviders.class})
class AppleOnlyLoginTest {
    @LocalServerPort
    private int port;

    @Test
    void 켜지지_않은_카카오는_시작할_수_없다() throws Exception {
        // when
        HttpResponse<String> kakao = new TestBrowser(port).get("/api/auth/kakao/start");

        // then
        assertThat(kakao.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }
}
