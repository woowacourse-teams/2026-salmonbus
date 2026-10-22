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
 *
 * <p>로그인만 켜고 제공자는 하나도 켜지 않은 운영 기본 설정을 검증하므로, 제공자를 모두 켜는
 * {@code LoginIntegrationTest} 와 컨텍스트를 함께 쓰지 않는다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "auth.enabled=true")
@Import(PostgresTestContainer.class)
class ApiSecurityContractTest {
    private static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
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
    void 임시_세션을_비운다() {
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
    }

    @Test
    void 로그인이_필요한_경로에_토큰이_없으면_401과_기존_오류_형식으로_답한다() throws Exception {
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
        HttpResponse<String> actual = send(request("/api/auth/logout")
            .header("Cookie", "__Host-sb_rt=any")
            .POST(HttpRequest.BodyPublishers.noBody()));

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
            .header("Cookie", CSRF_COOKIE + "=" + token + "; __Host-sb_rt=any")
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
    void Bearer_형식이_아닌_Authorization_헤더는_쿠키_인증의_CSRF_검사를_면제하지_않는다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Authorization", "Basic abc")
            .header("Cookie", "__Host-sb_at=any")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nickname\":\"새이름\"}")));

        // then
        assertThat(code(actual)).isEqualTo("CSRF_REJECTED");
    }

    @Test
    void CSRF_토큰을_폼_파라미터로는_받지_않는다() throws Exception {
        // given
        String token = cookieValue(send(request("/api/auth/session").GET()), CSRF_COOKIE);

        // when
        HttpResponse<String> actual = send(request("/api/auth/logout?_csrf=" + token)
            .header("Cookie", CSRF_COOKIE + "=" + token + "; __Host-sb_rt=any")
            .POST(HttpRequest.BodyPublishers.noBody()));

        // then
        assertThat(code(actual)).isEqualTo("CSRF_REJECTED");
    }

    @Test
    void CSRF_쿠키는_호스트_전용_속성으로_내려간다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/auth/session").GET());

        // then
        String setCookie = setCookies(actual).stream()
            .filter(cookie -> cookie.startsWith(CSRF_COOKIE + "="))
            .findFirst()
            .orElse("");
        assertSoftly(softly -> {
            softly.assertThat(setCookie).contains("Secure", "Path=/", "SameSite=Lax");
            softly.assertThat(setCookie).doesNotContain("Domain=");
        });
    }

    @Test
    void 인증_쿠키가_없는_요청은_CSRF_토큰_없이도_로그아웃이_204다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/auth/logout").POST(HttpRequest.BodyPublishers.noBody()));

        // then
        assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void Authorization_헤더로_인증하는_요청은_CSRF_토큰을_요구하지_않는다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Authorization", "Bearer not-a-token")
            .header("Cookie", "__Host-sb_rt=any")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nickname\":\"새이름\"}")));

        // then
        assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void 깨진_인증_쿠키가_실려도_공개_조회는_그대로_성공한다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/v1/routes")
            .header("Cookie", "__Host-sb_at=garbage; __Host-sb_rt=garbage")
            .header("Authorization", "Bearer garbage")
            .GET());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("max-age=300, public");
            softly.assertThat(setCookies(actual)).isEmpty();
        });
    }

    @Test
    void 만료되거나_깨진_토큰으로_내_정보를_부르면_401이다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me").header("Cookie", "__Host-sb_at=garbage").GET());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
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

    private String code(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).path("code").asString();
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
