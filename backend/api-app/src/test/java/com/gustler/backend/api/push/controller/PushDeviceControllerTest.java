package com.gustler.backend.api.push.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.support.PostgresTestContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "auth.enabled=true")
@Import(PostgresTestContainer.class)
class PushDeviceControllerTest {
    private static final String PATH = "/api/me/push-devices";
    private static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private SignInWithSocialAccount signIn;
    @Autowired
    private AccessTokens accessTokens;
    @Autowired
    private ObjectMapper json;

    private UUID memberId;
    private String accessCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM member").update();
        memberId = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "push-member")).publicId();
        accessCookie = cookie(memberId);
        HttpResponse<String> session = client.send(request("/api/auth/session").GET().build(),
            HttpResponse.BodyHandlers.ofString());
        csrfToken = session.headers().allValues("Set-Cookie").stream()
            .filter(value -> value.startsWith(CSRF_COOKIE + "="))
            .map(value -> value.substring(CSRF_COOKIE.length() + 1).split(";", 2)[0])
            .findFirst().orElseThrow();
    }

    @Test
    void cookieAndCsrfRegistrationCreatesAndTransfersOneDevice() throws Exception {
        HttpResponse<String> first = send(false, "{\"token\":\"ABCDEF\",\"environment\":\"SANDBOX\"}", accessCookie, true);
        assertNoContent(first);
        assertThat(count()).isEqualTo(1);
        UUID other = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "other-push-member")).publicId();
        HttpResponse<String> second = send(false, "{\"token\":\"abcdef\",\"environment\":\"PRODUCTION\"}", cookie(other), true);
        assertNoContent(second);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT m.public_id FROM push_device p JOIN member m ON m.id = p.member_id")
            .query(UUID.class).single()).isEqualTo(other);
        assertThat(jdbc.sql("SELECT environment FROM push_device").query(String.class).single()).isEqualTo("PRODUCTION");
    }

    @Test
    void unregisterIsScopedToOwnerAndIdempotent() throws Exception {
        assertNoContent(send(false, "{\"token\":\"AB\",\"environment\":\"SANDBOX\"}", accessCookie, true));
        UUID other = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "other-push-member")).publicId();
        assertNoContent(send(true, "{\"token\":\"ab\"}", cookie(other), true));
        assertNoContent(send(true, "{\"token\":\"cc\"}", accessCookie, true));
        assertThat(count()).isEqualTo(1);
        assertNoContent(send(true, "{\"token\":\"AB\"}", accessCookie, true));
        assertNoContent(send(true, "{\"token\":\"ab\"}", accessCookie, true));
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anonymousRequestIsUnauthorized(boolean unregister) throws Exception {
        assertError(send(unregister, validBody(), "", true), 401, "UNAUTHENTICATED");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cookieWithoutCsrfIsRejected(boolean unregister) throws Exception {
        assertError(send(unregister, validBody(), accessCookie, false), 400, "CSRF_REJECTED");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void withdrawnMemberIsUnauthorized(boolean unregister) throws Exception {
        jdbc.sql("DELETE FROM member WHERE public_id = ?").param(memberId).update();
        assertError(send(unregister, validBody(), accessCookie, true), 401, "UNAUTHENTICATED");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "false|{}",
        "false|{\"token\":null,\"environment\":\"SANDBOX\"}",
        "false|{\"token\":\"\",\"environment\":\"SANDBOX\"}",
        "false|{\"token\":\"xyz\",\"environment\":\"SANDBOX\"}",
        "false|{\"token\":\" ab\",\"environment\":\"SANDBOX\"}",
        "false|{\"token\":\"ab\",\"environment\":\"sandbox\"}",
        "false|{\"token\":\"ab\",\"environment\":null}",
        "true|{}",
        "true|{\"token\":null}",
        "true|{\"token\":\"\"}",
        "true|{\"token\":\"private-invalid-token\"}"
    })
    void invalidFieldsUsePushDeviceError(boolean unregister, String body) throws Exception {
        HttpResponse<String> response = send(unregister, body, accessCookie, true);
        assertError(response, 400, "INVALID_PUSH_DEVICE");
        assertThat(response.body()).doesNotContain("private-invalid-token");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tooLongTokenIsRejected(boolean unregister) throws Exception {
        String body = "{\"token\":\"" + "a".repeat(201) + "\",\"environment\":\"SANDBOX\"}";
        assertError(send(unregister, body, accessCookie, true), 400, "INVALID_PUSH_DEVICE");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedJsonUsesInvalidRequest(boolean unregister) throws Exception {
        assertError(send(unregister, "not json", accessCookie, true), 400, "INVALID_REQUEST");
        assertThat(count()).isZero();
    }

    private HttpResponse<String> send(boolean unregister, String body, String access, boolean csrf) throws Exception {
        HttpRequest.Builder builder = request(PATH + (unregister ? "/unregister" : ""))
            .header("Content-Type", "application/json");
        String cookies = access;
        if (csrf) {
            cookies += (cookies.isEmpty() ? "" : "; ") + CSRF_COOKIE + "=" + csrfToken;
            builder.header("X-XSRF-TOKEN", csrfToken);
        }
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies);
        }
        builder.method(unregister ? "POST" : "PUT", HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String validBody() {
        return "{\"token\":\"ab\",\"environment\":\"SANDBOX\"}";
    }

    private String cookie(UUID member) {
        return "__Host-sb_at=" + accessTokens.issue(member);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM push_device").query(Long.class).single();
    }

    private void assertNoContent(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
}
