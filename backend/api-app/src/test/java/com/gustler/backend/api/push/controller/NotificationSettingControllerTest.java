package com.gustler.backend.api.push.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.favorite.application.DefaultFavoriteGroupService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "auth.enabled=true")
@Import(PostgresTestContainer.class)
class NotificationSettingControllerTest {
    private static final String PATH = "/api/me/notification-settings";
    private static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
    private static final String ROUTE = "234001739";
    private static final String STOP = "214002018";
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
    @Autowired
    private DefaultFavoriteGroupService defaultGroups;

    private UUID memberId;
    private String accessCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM member").update();
        memberId = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "notice-member")).publicId();
        defaultGroups.ensure(memberId);
        accessCookie = cookie(memberId);
        HttpResponse<String> session = client.send(request("/api/auth/session").GET().build(),
            HttpResponse.BodyHandlers.ofString());
        csrfToken = session.headers().allValues("Set-Cookie").stream()
            .filter(value -> value.startsWith(CSRF_COOKIE + "="))
            .map(value -> value.substring(CSRF_COOKIE.length() + 1).split(";", 2)[0])
            .findFirst().orElseThrow();
    }

    @Test
    void 저장한_설정은_초_없는_시각과_요일_이름으로_돌아온다() throws Exception {
        addFavorite(memberId, ROUTE, STOP, "UP");
        HttpResponse<String> saved = put(validBody("07:30", "[\"TUE\",\"MON\"]"), accessCookie, true);
        assertSoftly(softly -> {
            softly.assertThat(saved.statusCode()).isEqualTo(200);
            softly.assertThat(saved.headers().firstValue("Cache-Control")).contains("no-store");
            JsonNode body = json.readTree(saved.body());
            softly.assertThat(body.path("time").asString()).isEqualTo("07:30");
            softly.assertThat(body.path("weekdays").toString()).isEqualTo("[\"MON\",\"TUE\"]");
            softly.assertThat(body.path("routeId").asString()).isEqualTo(ROUTE);
            softly.assertThat(body.has("occurrence_at")).isFalse();
        });
        HttpResponse<String> listed = send("GET", PATH, null, accessCookie, true);
        JsonNode settings = json.readTree(listed.body()).path("settings");
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(settings.size()).isEqualTo(1);
        assertThat(settings.path(0).path("time").asString()).isEqualTo("07:30");
        assertThat(settings.path(0).path("weekdays").toString()).isEqualTo("[\"MON\",\"TUE\"]");
        assertThat(settings.path(0).path("id").asString())
            .isEqualTo(json.readTree(saved.body()).path("id").asString());
    }

    @Test
    void 같은_대상은_한_행이고_id를_유지한다() throws Exception {
        addFavorite(memberId, ROUTE, STOP, "UP");
        String firstId = json.readTree(put(validBody("07:40", weekdaysJson()), accessCookie, true).body())
            .path("id").asString();
        HttpResponse<String> second = put(validBody("08:00", weekdaysJson()), accessCookie, true);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body()).path("id").asString()).isEqualTo(firstId);
        assertThat(json.readTree(second.body()).path("time").asString()).isEqualTo("08:00");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 즐겨찾기에_없으면_422다() throws Exception {
        HttpResponse<String> response = put(validBody("07:40", weekdaysJson()), accessCookie, true);
        assertError(response, 422, "NOTIFICATION_TARGET_NOT_FAVORITE");
        assertThat(count()).isZero();
    }

    @Test
    void 빈_요일과_초가_있는_시각은_400이다() throws Exception {
        addFavorite(memberId, ROUTE, STOP, "UP");
        assertError(put(validBody("07:40", "[]"), accessCookie, true), 400, "INVALID_NOTIFICATION_SCHEDULE");
        assertError(put(validBody("07:40:00", weekdaysJson()), accessCookie, true), 400, "INVALID_NOTIFICATION_SCHEDULE");
        assertError(put("""
            {"routeId":"%s","stopId":"%s","direction":"UP","time":"07:40"}
            """.formatted(ROUTE, STOP), accessCookie, true), 400, "INVALID_NOTIFICATION_SCHEDULE");
        assertError(put(validBody("07:40", "[\"MON\",\"MON\"]"), accessCookie, true), 400, "INVALID_NOTIFICATION_SCHEDULE");
        assertThat(count()).isZero();
    }

    @Test
    void 대상_형식이_틀리면_INVALID_REQUEST다() throws Exception {
        addFavorite(memberId, ROUTE, STOP, "UP");
        assertError(put("""
            {"routeId":"abc","stopId":"%s","direction":"UP","time":"07:40","weekdays":%s}
            """.formatted(STOP, weekdaysJson()), accessCookie, true), 400, "INVALID_REQUEST");
        assertError(put("""
            {"routeId":"abc","stopId":"%s","direction":"UP","time":"07:40:00","weekdays":[]}
            """.formatted(STOP), accessCookie, true), 400, "INVALID_REQUEST");
        assertThat(count()).isZero();
    }

    @Test
    void UUID가_아닌_삭제는_INVALID_REQUEST다() throws Exception {
        HttpResponse<String> response = send("DELETE", PATH + "/not-a-uuid", null, accessCookie, true);
        assertError(response, 400, "INVALID_REQUEST");
    }

    @Test
    void 남의_설정은_404이고_행이_남는다() throws Exception {
        addFavorite(memberId, ROUTE, STOP, "UP");
        String id = json.readTree(put(validBody("07:40", weekdaysJson()), accessCookie, true).body())
            .path("id").asString();
        UUID other = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "other-notice-member")).publicId();
        HttpResponse<String> response = send("DELETE", PATH + "/" + id, null, cookie(other), true);
        assertError(response, 404, "NOTIFICATION_SETTING_NOT_FOUND");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 그룹_이름을_바꿔도_설정이_남는다() throws Exception {
        addFavorite(memberId, ROUTE, STOP, "UP");
        String id = json.readTree(put(validBody("07:40", weekdaysJson()), accessCookie, true).body())
            .path("id").asString();
        UUID groupId = jdbc.sql("""
            SELECT fg.id FROM favorite_group fg
            JOIN member m ON m.id = fg.member_id
            WHERE m.public_id = ?
            """).param(memberId).query(UUID.class).single();
        HttpResponse<String> renamed = send(
            "PATCH",
            "/api/me/favorite-groups/" + groupId,
            "{\"name\":\"출근\"}",
            accessCookie,
            true
        );
        assertThat(renamed.statusCode()).isEqualTo(200);
        HttpResponse<String> listed = send("GET", PATH, null, accessCookie, true);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(listed.body()).path("settings").path(0).path("id").asString()).isEqualTo(id);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 즐겨찾기에서_빠져도_GET에_보인다() throws Exception {
        addFavorite(memberId, ROUTE, STOP, "UP");
        String id = json.readTree(put(validBody("07:40", weekdaysJson()), accessCookie, true).body())
            .path("id").asString();
        jdbc.sql("DELETE FROM favorite").update();
        HttpResponse<String> listed = send("GET", PATH, null, accessCookie, true);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(listed.body()).path("settings").path(0).path("id").asString()).isEqualTo(id);
    }

    @Test
    void CSRF_없이_쿠키로_저장하면_거절한다() throws Exception {
        addFavorite(memberId, ROUTE, STOP, "UP");
        assertError(put(validBody("07:40", weekdaysJson()), accessCookie, false), 400, "CSRF_REJECTED");
        assertThat(count()).isZero();
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        assertError(put(validBody("07:40", weekdaysJson()), "", true), 401, "UNAUTHENTICATED");
    }

    private HttpResponse<String> put(String body, String access, boolean csrf) throws Exception {
        return send("PUT", PATH, body, access, csrf);
    }

    private HttpResponse<String> send(
        String method,
        String path,
        String body,
        String access,
        boolean csrf
    ) throws Exception {
        HttpRequest.Builder builder = request(path);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        String cookies = access;
        if (csrf) {
            cookies += (cookies.isEmpty() ? "" : "; ") + CSRF_COOKIE + "=" + csrfToken;
            builder.header("X-XSRF-TOKEN", csrfToken);
        }
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies);
        }
        HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body);
        builder.method(method, publisher);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void addFavorite(UUID memberPublicId, String routeId, String stopId, String direction) {
        jdbc.sql("""
            INSERT INTO favorite (id, favorite_group_id, route_id, stop_id, direction, position)
            SELECT ?, favorite_group.id, ?, ?, ?, 0
            FROM favorite_group
            JOIN member ON member.id = favorite_group.member_id
            WHERE member.public_id = ?
            """).params(UUID.randomUUID(), routeId, stopId, direction, memberPublicId).update();
    }

    private String validBody(String time, String weekdays) {
        return """
            {"routeId":"%s","stopId":"%s","direction":"UP","time":"%s","weekdays":%s}
            """.formatted(ROUTE, STOP, time, weekdays);
    }

    private static String weekdaysJson() {
        return "[\"MON\",\"TUE\",\"WED\",\"THU\",\"FRI\"]";
    }

    private String cookie(UUID member) {
        return "__Host-sb_at=" + accessTokens.issue(member, null);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM notification_setting").query(Long.class).single();
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
}
