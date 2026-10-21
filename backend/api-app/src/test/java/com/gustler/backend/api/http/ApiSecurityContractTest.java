package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.PostgresTestContainer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인을 도입해도 공개 조회 API와 오류 응답 형식이 그대로인지 실제 톰캣으로 검증한다.
 *
 * <p>요청 ID 필터와 세션 필터, 보안 필터가 모두 실행되어야 하므로 MockMvc 대신 HTTP 요청을 보낸다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "auth.enabled=true")
@Import(PostgresTestContainer.class)
class ApiSecurityContractTest {
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "__Host-sb_session";

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void 세션을_비운다() {
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
    }

    @Test
    void 로그인이_필요한_경로에_세션이_없으면_401과_기존_오류_형식으로_답한다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me").GET());

        // then
        JsonNode body = objectMapper.readTree(actual.body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(body.path("code").asString()).isEqualTo("UNAUTHENTICATED");
            softly.assertThat(body.path("requestId").asString()).isEqualTo(header(actual, RequestId.HEADER));
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(header(actual, "Content-Type")).startsWith("application/json");
        });
    }

    @Test
    void CSRF_토큰_없이_상태를_바꾸면_403이_아니라_400으로_답한다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/auth/logout").POST(HttpRequest.BodyPublishers.noBody()));

        // then
        JsonNode body = objectMapper.readTree(actual.body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(body.path("code").asString()).isEqualTo("CSRF_REJECTED");
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
        });
    }

    @Test
    void 로그인_상태를_물으면_비로그인으로_답하고_CSRF_쿠키를_내려준다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/auth/session").GET());

        // then
        JsonNode body = objectMapper.readTree(actual.body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(body.path("authenticated").asBoolean()).isFalse();
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(cookieValue(actual, CSRF_COOKIE)).isNotBlank();
            softly.assertThat(setCookies(actual)).noneMatch(cookie -> cookie.startsWith(SESSION_COOKIE));
            softly.assertThat(sessionCount()).isZero();
        });
    }

    @Test
    void CSRF_토큰을_돌려보내면_로그아웃이_204로_끝난다() throws Exception {
        // given
        String token = cookieValue(send(request("/api/auth/session").GET()), CSRF_COOKIE);

        // when
        HttpResponse<String> actual = send(request("/api/auth/logout")
            .header("Cookie", CSRF_COOKIE + "=" + token)
            .header(CSRF_HEADER, token)
            .POST(HttpRequest.BodyPublishers.noBody()));

        // then
        assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void 공개_조회는_세션_쿠키를_만들지_않고_캐시_정책을_그대로_둔다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/v1/routes")
            .header("Cookie", SESSION_COOKIE + "=unknown")
            .GET());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("max-age=300, public");
            softly.assertThat(setCookies(actual)).isEmpty();
            softly.assertThat(sessionCount()).isZero();
        });
    }

    @Test
    void 배포_검증_경로는_로그인_없이_열린다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/readyz").GET());

        // then
        assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private static List<String> setCookies(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie");
    }

    private static String cookieValue(HttpResponse<String> response, String name) {
        return setCookies(response).stream()
            .filter(cookie -> cookie.startsWith(name + "="))
            .map(cookie -> cookie.substring(name.length() + 1).split(";", 2)[0])
            .findFirst()
            .orElse("");
    }

    private long sessionCount() {
        return jdbcClient.sql("SELECT count(*) FROM SPRING_SESSION").query(Long.class).single();
    }
}
