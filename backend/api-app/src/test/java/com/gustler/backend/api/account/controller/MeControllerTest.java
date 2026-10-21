package com.gustler.backend.api.account.controller;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.support.PostgresTestContainer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인한 회원의 정보 조회와 닉네임 변경을 실제 톰캣으로 검증한다.
 *
 * <p>카카오 흐름은 {@code KakaoLoginFlowTest} 가 검증하므로 여기서는 세션을 직접 만들어 쿠키로 보낸다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "auth.enabled=true")
@Import(PostgresTestContainer.class)
class MeControllerTest {
    private static final String SESSION_COOKIE = "__Host-sb_session";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SignInWithSocialAccount signIn;

    @Autowired
    @SuppressWarnings("rawtypes")
    private SessionRepository sessions;

    private UUID memberId;
    private String sessionCookie;
    private String csrfToken;

    @BeforeEach
    void 회원과_로그인_세션을_만든다() throws Exception {
        jdbcClient.sql("DELETE FROM member").update();
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
        memberId = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "kakao-1", "연어팬")).publicId();
        sessionCookie = loginSession(memberId);
        csrfToken = csrfToken();
    }

    @Test
    void 내_정보를_조회한다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me").header("Cookie", sessionCookie).GET());

        // then
        JsonNode body = objectMapper.readTree(actual.body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(body.path("id").asString()).isEqualTo(memberId.toString());
            softly.assertThat(body.path("nickname").asString()).isEqualTo("연어팬");
            softly.assertThat(body.path("provider").asString()).isEqualTo("KAKAO");
            softly.assertThat(body.path("createdAt").asString()).isNotBlank();
            softly.assertThat(body.has("email")).isFalse();
        });
    }

    @Test
    void 세션이_없으면_401이다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me").GET());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void 탈퇴한_회원의_세션이_남아_있으면_401이다() throws Exception {
        // given
        jdbcClient.sql("DELETE FROM member").update();

        // when
        HttpResponse<String> actual = send(request("/api/me").header("Cookie", sessionCookie).GET());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void 탈퇴한_회원의_세션으로_닉네임을_바꾸려_하면_401이다() throws Exception {
        // given
        jdbcClient.sql("DELETE FROM member").update();

        // when
        HttpResponse<String> actual = patch("{\"nickname\":\"새이름\"}");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void 닉네임을_바꾸면_응답과_다음_조회에_반영된다() throws Exception {
        // when
        HttpResponse<String> actual = patch("{\"nickname\":\"  새이름  \"}");

        // then
        JsonNode body = objectMapper.readTree(actual.body());
        HttpResponse<String> lookup = send(request("/api/me").header("Cookie", sessionCookie).GET());
        JsonNode afterwards = objectMapper.readTree(lookup.body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(body.path("nickname").asString()).isEqualTo("새이름");
            softly.assertThat(afterwards.path("nickname").asString()).isEqualTo("새이름");
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"nickname\":\"a\"}|NICKNAME_LENGTH",
        "{\"nickname\":\"가나다라마바사아자차카\"}|NICKNAME_LENGTH",
        "{\"nickname\":\"\"}|NICKNAME_LENGTH",
        "{}|NICKNAME_LENGTH",
        "{\"nickname\":null}|NICKNAME_LENGTH",
        "{\"nickname\":\"연어 버스\"}|NICKNAME_CHARACTERS",
        "{\"nickname\":\"ㄱㄴㄷ\"}|NICKNAME_CHARACTERS",
        "{\"nickname\":\"관리자1\"}|NICKNAME_RESERVED",
        "{\"nickname\":\"ADMIN1\"}|NICKNAME_RESERVED",
    })
    void 규칙을_어기면_400과_어긴_규칙의_코드로_답한다(String requestBody, String expected) throws Exception {
        // when
        HttpResponse<String> actual = patch(requestBody);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo(expected);
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    @Test
    void 다른_회원이_쓰는_닉네임이면_대소문자가_달라도_409다() throws Exception {
        // given
        signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "kakao-2", "Salmon"));

        // when
        HttpResponse<String> actual = patch("{\"nickname\":\"SALMON\"}");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
            softly.assertThat(code(actual)).isEqualTo("NICKNAME_TAKEN");
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    @Test
    void 자기_닉네임과_같은_값으로_바꾸는_것은_성공한다() throws Exception {
        // when
        HttpResponse<String> actual = patch("{\"nickname\":\"연어팬\"}");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    @Test
    void CSRF_토큰이_없으면_닉네임을_바꿀_수_없다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Cookie", sessionCookie)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nickname\":\"새이름\"}")));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo("CSRF_REJECTED");
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    @Test
    void 세션이_없으면_CSRF_토큰이_있어도_401이다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Cookie", CSRF_COOKIE + "=" + csrfToken)
            .header("X-XSRF-TOKEN", csrfToken)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nickname\":\"새이름\"}")));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void 본문이_JSON이_아니면_400_INVALID_REQUEST다() throws Exception {
        // when
        HttpResponse<String> actual = patch("not json");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo("INVALID_REQUEST");
            softly.assertThat(nickname()).isEqualTo("연어팬");
        });
    }

    private HttpResponse<String> patch(String body) throws IOException, InterruptedException {
        return send(request("/api/me")
            .header("Cookie", sessionCookie + "; " + CSRF_COOKIE + "=" + csrfToken)
            .header("X-XSRF-TOKEN", csrfToken)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body)));
    }

    @SuppressWarnings("unchecked")
    private String loginSession(UUID publicId) {
        Session session = sessions.createSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            new MemberPrincipal(publicId), null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        sessions.save(session);
        String encodedId = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
        return SESSION_COOKIE + "=" + encodedId;
    }

    private String csrfToken() throws IOException, InterruptedException {
        HttpResponse<String> response = send(request("/api/auth/session").GET());
        return response.headers().allValues("Set-Cookie").stream()
            .filter(cookie -> cookie.startsWith(CSRF_COOKIE + "="))
            .map(cookie -> cookie.substring(CSRF_COOKIE.length() + 1).split(";", 2)[0])
            .findFirst()
            .orElseThrow();
    }

    private String nickname() {
        return jdbcClient.sql("SELECT nickname FROM member WHERE public_id = ?")
            .param(memberId)
            .query(String.class)
            .single();
    }

    private String code(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).path("code").asString();
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
}
