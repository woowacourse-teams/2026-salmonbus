package com.gustler.backend.api.http;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.PostgresTestContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/**
 * 로그인 스위치에 {@code true} 가 아닌 켜짐 비슷한 값을 넣으면 보안 규칙도 컨트롤러처럼 꺼짐으로 읽는다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "auth.enabled=1")
@Import(PostgresTestContainer.class)
class LoginSwitchValueTest {
    private static final String STALE_AUTH_COOKIES = "__Host-sb_at=stale-access; __Host-sb_rt=stale-refresh";

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @ParameterizedTest
    @CsvSource({
        "GET, /api/me",
        "POST, /api/auth/refresh",
    })
    void 스위치가_1이면_로그인_경로는_남은_인증_쿠키가_있어도_404다(String method, String path) throws Exception {
        // given
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .header("Cookie", STALE_AUTH_COOKIES)
            .build();

        // when
        HttpResponse<String> actual = client.send(request, HttpResponse.BodyHandlers.ofString());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
            softly.assertThat(actual.body()).contains("ENDPOINT_NOT_FOUND");
        });
    }
}
