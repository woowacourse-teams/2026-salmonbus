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

/** 로그인 전체 스위치를 끄면 제공자 스위치가 켜져 있어도 로그인 경로가 열리지 않는다. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "auth.enabled=false",
    "auth.kakao.enabled=true",
    "auth.kakao.client-id=unused",
    "auth.kakao.client-secret=unused",
})
@Import(PostgresTestContainer.class)
class KakaoLoginMasterSwitchTest {
    @LocalServerPort
    private int port;

    @Test
    void 로그인_전체를_끄면_카카오_시작_경로가_없다() throws Exception {
        // when
        HttpResponse<String> actual = new TestBrowser(port).get("/api/auth/kakao/start");

        // then
        assertThat(actual.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }
}
