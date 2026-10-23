package com.gustler.backend.api.http;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.PostgresTestContainer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인이 꺼진 기본 설정에서 로그인 경로가 모두 없는 경로로 답하는지 실제 톰캣으로 검증한다.
 *
 * <p>로그인을 켰다가 끈 뒤에도 브라우저에는 인증 쿠키가 남을 수 있어, 쿠키가 있을 때와 없을 때를 모두 본다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class LoginDisabledTest {
    private static final String STALE_AUTH_COOKIES = "__Host-sb_at=stale-access; __Host-sb_rt=stale-refresh";

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    static Stream<Arguments> 로그인_경로() {
        return Stream.of(
            Arguments.of("GET", "/api/auth/session"),
            Arguments.of("GET", "/api/auth/kakao/start"),
            Arguments.of("GET", "/api/auth/apple/start"),
            Arguments.of("GET", "/api/auth/kakao/callback?code=code&state=state"),
            Arguments.of("GET", "/api/auth/apple/callback?code=code&state=state"),
            Arguments.of("POST", "/api/auth/refresh"),
            Arguments.of("POST", "/api/auth/logout"),
            Arguments.of("GET", "/api/me"),
            Arguments.of("GET", "/api/me/favorite-groups"),
            Arguments.of("GET", "/api/me/favorites"),
            Arguments.of("PUT", "/api/me/favorite-order"),
            Arguments.of("PATCH", "/api/me"),
            Arguments.of("DELETE", "/api/me"),
            Arguments.of("DELETE", "/api/me/sessions"),
            Arguments.of("POST", "/api/me/withdrawal/apple"));
    }

    @ParameterizedTest
    @MethodSource("로그인_경로")
    void 로그인을_끄면_인증_쿠키가_없어도_로그인_경로는_404다(String method, String path) throws Exception {
        // when
        HttpResponse<String> actual = send(method, path, "");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
            softly.assertThat(code(actual)).isEqualTo("ENDPOINT_NOT_FOUND");
        });
    }

    @ParameterizedTest
    @MethodSource("로그인_경로")
    void 로그인을_끄면_남은_인증_쿠키가_있어도_로그인_경로는_404다(String method, String path) throws Exception {
        // when
        HttpResponse<String> actual = send(method, path, STALE_AUTH_COOKIES);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
            softly.assertThat(code(actual)).isEqualTo("ENDPOINT_NOT_FOUND");
            softly.assertThat(actual.headers().allValues("Set-Cookie")).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({
        "/api/v1/routes",
        "/readyz",
    })
    void 로그인을_꺼도_공개_조회와_배포_검증_경로는_인증_쿠키와_상관없이_열린다(String path) throws Exception {
        // when
        HttpResponse<String> withoutCookies = send("GET", path, "");
        HttpResponse<String> withCookies = send("GET", path, STALE_AUTH_COOKIES);

        // then
        assertSoftly(softly -> {
            softly.assertThat(withoutCookies.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(withCookies.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(withCookies.headers().allValues("Set-Cookie")).isEmpty();
        });
    }

    private HttpResponse<String> send(
        String method,
        String path,
        String cookies
    ) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String code(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).path("code").asString();
    }
}
