package com.gustler.backend.api.push.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.support.PostgresTestContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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

    private enum DeviceRequest {
        REGISTER("PUT", PATH),
        UNREGISTER("POST", PATH + "/unregister");

        private final String method;
        private final String path;

        DeviceRequest(String method, String path) {
            this.method = method;
            this.path = path;
        }
    }

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
    @Autowired
    private RefreshTokens refreshTokens;

    private UUID memberId;
    private String accessCookie;
    private String csrfToken;
    private String refreshToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM member").update();
        memberId = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "push-member")).publicId();
        HttpResponse<String> refreshed = refresh(refreshTokens.issue(memberId).token());
        assertThat(refreshed.statusCode()).isEqualTo(200);
        accessCookie = accessCookie(refreshed);
        refreshToken = json.readTree(refreshed.body()).path("refreshToken").asString();
        HttpResponse<String> session = client.send(request("/api/auth/session").GET().build(),
            HttpResponse.BodyHandlers.ofString());
        csrfToken = session.headers().allValues("Set-Cookie").stream()
            .filter(value -> value.startsWith(CSRF_COOKIE + "="))
            .map(value -> value.substring(CSRF_COOKIE.length() + 1).split(";", 2)[0])
            .findFirst().orElseThrow();
    }

    @Test
    void 쿠키와_CSRF로_등록하고_다른_회원이_같은_토큰을_등록하면_그_회원의_기기가_된다() throws Exception {
        HttpResponse<String> first = send(DeviceRequest.REGISTER, "{\"token\":\"ABCDEF\",\"environment\":\"SANDBOX\"}", accessCookie, true);
        assertNoContent(first);
        assertThat(count()).isEqualTo(1);
        UUID other = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "other-push-member")).publicId();
        HttpResponse<String> second = send(DeviceRequest.REGISTER, "{\"token\":\"abcdef\",\"environment\":\"PRODUCTION\"}", cookie(other), true);
        assertNoContent(second);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT m.public_id FROM push_device p JOIN member m ON m.id = p.member_id")
            .query(UUID.class).single()).isEqualTo(other);
        assertThat(jdbc.sql("SELECT environment FROM push_device").query(String.class).single()).isEqualTo("PRODUCTION");
    }

    @Test
    void 해제는_자기_토큰만_지우고_여러_번_보내도_같다() throws Exception {
        assertNoContent(send(DeviceRequest.REGISTER, "{\"token\":\"AB\",\"environment\":\"SANDBOX\"}", accessCookie, true));
        UUID other = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "other-push-member")).publicId();
        assertNoContent(send(DeviceRequest.UNREGISTER, "{\"token\":\"ab\"}", cookie(other), true));
        assertNoContent(send(DeviceRequest.UNREGISTER, "{\"token\":\"cc\"}", accessCookie, true));
        assertThat(count()).isEqualTo(1);
        assertNoContent(send(DeviceRequest.UNREGISTER, "{\"token\":\"AB\"}", accessCookie, true));
        assertNoContent(send(DeviceRequest.UNREGISTER, "{\"token\":\"ab\"}", accessCookie, true));
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(DeviceRequest.class)
    void 로그인하지_않았으면_401이다(DeviceRequest deviceRequest) throws Exception {
        assertError(send(deviceRequest, validBody(), "", true), 401, "UNAUTHENTICATED");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(DeviceRequest.class)
    void 쿠키로_보내면서_CSRF_토큰이_없으면_400이다(DeviceRequest deviceRequest) throws Exception {
        assertError(send(deviceRequest, validBody(), accessCookie, false), 400, "CSRF_REJECTED");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(DeviceRequest.class)
    void 탈퇴한_회원은_401이다(DeviceRequest deviceRequest) throws Exception {
        jdbc.sql("DELETE FROM member WHERE public_id = ?").param(memberId).update();
        assertError(send(deviceRequest, validBody(), accessCookie, true), 401, "UNAUTHENTICATED");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "REGISTER|{}",
        "REGISTER|{\"token\":null,\"environment\":\"SANDBOX\"}",
        "REGISTER|{\"token\":\"\",\"environment\":\"SANDBOX\"}",
        "REGISTER|{\"token\":\"xyz\",\"environment\":\"SANDBOX\"}",
        "REGISTER|{\"token\":\" ab\",\"environment\":\"SANDBOX\"}",
        "REGISTER|{\"token\":\"ab\",\"environment\":\"sandbox\"}",
        "REGISTER|{\"token\":\"ab\",\"environment\":null}",
        "UNREGISTER|{}",
        "UNREGISTER|{\"token\":null}",
        "UNREGISTER|{\"token\":\"\"}",
        "UNREGISTER|{\"token\":\"private-invalid-token\"}"
    })
    void 필드가_틀리면_INVALID_PUSH_DEVICE이고_토큰을_응답에_싣지_않는다(DeviceRequest deviceRequest, String body) throws Exception {
        HttpResponse<String> response = send(deviceRequest, body, accessCookie, true);
        assertError(response, 400, "INVALID_PUSH_DEVICE");
        assertThat(response.body()).doesNotContain("private-invalid-token");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(DeviceRequest.class)
    void 토큰이_200자를_넘으면_400이다(DeviceRequest deviceRequest) throws Exception {
        String body = "{\"token\":\"" + "a".repeat(201) + "\",\"environment\":\"SANDBOX\"}";
        assertError(send(deviceRequest, body, accessCookie, true), 400, "INVALID_PUSH_DEVICE");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(DeviceRequest.class)
    void JSON_형식이_틀리면_INVALID_REQUEST다(DeviceRequest deviceRequest) throws Exception {
        assertError(send(deviceRequest, "not json", accessCookie, true), 400, "INVALID_REQUEST");
        assertThat(count()).isZero();
    }

    @Test
    void 로그아웃하면_그_세션의_기기를_지운다() throws Exception {
        assertNoContent(send(DeviceRequest.REGISTER, validBody(), accessCookie, true));

        assertNoContent(logout(refreshToken));

        assertThat(count()).isZero();
    }

    @Test
    void 로그아웃한_뒤_등록하면_401이고_기기를_만들지_않는다() throws Exception {
        assertNoContent(logout(refreshToken));

        assertError(send(DeviceRequest.REGISTER, validBody(), accessCookie, true), 401, "UNAUTHENTICATED");

        assertThat(count()).isZero();
    }

    @Test
    void 로그아웃한_회원이_다른_회원의_토큰을_늦게_등록해도_다른_회원의_기기는_남는다() throws Exception {
        UUID other = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "other-push-member")).publicId();
        assertNoContent(send(DeviceRequest.REGISTER, validBody(), cookie(other), true));
        assertNoContent(logout(refreshToken));

        assertError(send(DeviceRequest.REGISTER, validBody(), accessCookie, true), 401, "UNAUTHENTICATED");

        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT m.public_id FROM push_device p JOIN member m ON m.id = p.member_id")
            .query(UUID.class).single()).isEqualTo(other);
    }

    @Test
    void 로그아웃한_세션은_다른_세션의_기기를_가져오지_못한다() throws Exception {
        String otherSession = cookie(memberId);
        assertNoContent(send(DeviceRequest.REGISTER, validBody(), otherSession, true));
        UUID ownerSession = jdbc.sql("SELECT session_family_id FROM push_device").query(UUID.class).single();
        assertNoContent(logout(refreshToken));

        assertError(send(DeviceRequest.REGISTER, validBody(), accessCookie, true), 401, "UNAUTHENTICATED");

        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT session_family_id FROM push_device").query(UUID.class).single())
            .isEqualTo(ownerSession);
    }

    @Test
    void 로그아웃해도_다른_세션의_기기는_남는다() throws Exception {
        assertNoContent(send(DeviceRequest.REGISTER, validBody(), accessCookie, true));
        assertNoContent(send(DeviceRequest.REGISTER, "{\"token\":\"cd\",\"environment\":\"PRODUCTION\"}", cookie(memberId), true));

        assertNoContent(logout(refreshToken));

        assertThat(jdbc.sql("SELECT token FROM push_device").query(String.class).list()).containsExactly("cd");
    }

    @Test
    void 모든_기기에서_로그아웃하면_그_회원의_기기만_지운다() throws Exception {
        assertNoContent(send(DeviceRequest.REGISTER, validBody(), accessCookie, true));
        assertNoContent(send(DeviceRequest.REGISTER, "{\"token\":\"cd\",\"environment\":\"SANDBOX\"}", cookie(memberId), true));
        UUID other = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "other-push-member")).publicId();
        assertNoContent(send(DeviceRequest.REGISTER, "{\"token\":\"ef\",\"environment\":\"PRODUCTION\"}", cookie(other), true));

        HttpResponse<String> response = client.send(request("/api/me/sessions")
            .header("Cookie", accessCookie + "; " + CSRF_COOKIE + "=" + csrfToken)
            .header("X-XSRF-TOKEN", csrfToken).DELETE().build(), HttpResponse.BodyHandlers.ofString());

        assertNoContent(response);
        assertThat(jdbc.sql("SELECT count(*) FROM push_device WHERE member_id = "
            + "(SELECT id FROM member WHERE public_id = ?)").param(memberId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT token FROM push_device").query(String.class).list()).containsExactly("ef");
        assertError(send(DeviceRequest.REGISTER, validBody(), accessCookie, true), 401, "UNAUTHENTICATED");
    }

    @Test
    void 토큰을_갱신하거나_유예_재응답을_받아도_세션과_기기가_남는다() throws Exception {
        assertNoContent(send(DeviceRequest.REGISTER, validBody(), accessCookie, true));
        long deviceId = jdbc.sql("SELECT id FROM push_device").query(Long.class).single();
        UUID sessionId = jdbc.sql("SELECT session_family_id FROM push_device").query(UUID.class).single();

        HttpResponse<String> refreshed = refresh(refreshToken);
        HttpResponse<String> replayed = refresh(refreshToken);

        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT id FROM push_device").query(Long.class).single()).isEqualTo(deviceId);
        assertThat(jdbc.sql("SELECT session_family_id FROM push_device").query(UUID.class).single()).isEqualTo(sessionId);
        for (HttpResponse<String> response : List.of(refreshed, replayed)) {
            String accessToken = json.readTree(response.body()).path("accessToken").asString();
            assertThat(accessTokens.decoder().decode(accessToken).getClaimAsString("sid")).isEqualTo(sessionId.toString());
            assertNoContent(send(DeviceRequest.REGISTER, validBody(), accessCookie(response), true));
        }
        assertThat(jdbc.sql("SELECT id FROM push_device").query(Long.class).single()).isEqualTo(deviceId);
    }

    @Test
    void sid_없는_토큰으로_등록하면_401이다() throws Exception {
        String legacyCookie = "__Host-sb_at=" + accessTokens.issue(memberId, null);

        assertError(send(DeviceRequest.REGISTER, validBody(), legacyCookie, true), 401, "UNAUTHENTICATED");

        assertThat(count()).isZero();
    }

    @Test
    void sid_없는_토큰으로도_자기_기기는_해제한다() throws Exception {
        assertNoContent(send(DeviceRequest.REGISTER, validBody(), accessCookie, true));
        String legacyCookie = "__Host-sb_at=" + accessTokens.issue(memberId, null);

        assertNoContent(send(DeviceRequest.UNREGISTER, "{\"token\":\"ab\"}", legacyCookie, true));

        assertThat(count()).isZero();
    }

    @Test
    void 다른_세션으로_옮긴_기기는_원래_세션이_로그아웃해도_남는다() throws Exception {
        assertNoContent(send(DeviceRequest.REGISTER, validBody(), accessCookie, true));
        String otherSession = cookie(memberId);
        assertNoContent(send(DeviceRequest.REGISTER, validBody(), otherSession, true));

        assertNoContent(logout(refreshToken));

        assertThat(count()).isEqualTo(1);
        assertNoContent(send(DeviceRequest.REGISTER, validBody(), otherSession, true));
    }

    @Test
    void Authorization_헤더로_등록하면_CSRF_없이_sid를_쓴다() throws Exception {
        HttpResponse<String> response = client.send(request(PATH)
            .header("Authorization", "Bearer " + accessCookie.substring("__Host-sb_at=".length()))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(validBody())).build(), HttpResponse.BodyHandlers.ofString());

        assertNoContent(response);
        assertThat(count()).isEqualTo(1);
        assertNoContent(logout(refreshToken));
        assertThat(count()).isZero();
    }

    private HttpResponse<String> logout(String token) throws Exception {
        return client.send(request("/api/auth/logout").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"" + token + "\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> refresh(String token) throws Exception {
        return client.send(request("/api/auth/refresh").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"" + token + "\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String accessCookie(HttpResponse<String> response) {
        return "__Host-sb_at=" + json.readTree(response.body()).path("accessToken").asString();
    }

    private HttpResponse<String> send(DeviceRequest deviceRequest, String body, String access, boolean csrf)
        throws Exception {
        HttpRequest.Builder builder = request(deviceRequest.path).header("Content-Type", "application/json");
        List<String> cookies = new ArrayList<>();
        if (!access.isEmpty()) {
            cookies.add(access);
        }
        if (csrf) {
            cookies.add(CSRF_COOKIE + "=" + csrfToken);
            builder.header("X-XSRF-TOKEN", csrfToken);
        }
        if (!cookies.isEmpty()) {
            builder.header("Cookie", String.join("; ", cookies));
        }
        builder.method(deviceRequest.method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String validBody() {
        return "{\"token\":\"ab\",\"environment\":\"SANDBOX\"}";
    }

    private String cookie(UUID member) throws Exception {
        HttpResponse<String> response = refresh(refreshTokens.issue(member).token());
        assertThat(response.statusCode()).isEqualTo(200);
        return accessCookie(response);
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
