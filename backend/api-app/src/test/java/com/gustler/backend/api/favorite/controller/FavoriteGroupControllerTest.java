package com.gustler.backend.api.favorite.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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
class FavoriteGroupControllerTest {
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private SignInWithSocialAccount signIn;
    @Autowired
    private AccessTokens accessTokens;

    private String bearer;

    @BeforeEach
    void 회원을_만든다() {
        jdbc.sql("DELETE FROM member").update();
        UUID memberId = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "favorite-member")).publicId();
        bearer = "Bearer " + accessTokens.issue(memberId);
    }

    @Test
    void 현재_회원의_즐겨찾기_그룹을_만들고_조회한다() throws Exception {
        HttpResponse<String> created = send("POST", "/api/me/favorite-groups", "{\"name\":\" 출근 \"}", bearer);
        HttpResponse<String> found = send("GET", "/api/me/favorite-groups", null, bearer);

        JsonNode body = json.readTree(found.body());
        assertSoftly(softly -> {
            softly.assertThat(created.statusCode()).isEqualTo(201);
            softly.assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
            softly.assertThat(found.statusCode()).isEqualTo(200);
            softly.assertThat(body.path("groups").size()).isEqualTo(1);
            softly.assertThat(body.path("groups").get(0).path("name").asString()).isEqualTo("출근");
            softly.assertThat(body.path("groups").get(0).path("favorites").isEmpty()).isTrue();
        });
    }

    @Test
    void 그룹이_없으면_조회할_때_출퇴근_그룹을_만든다() throws Exception {
        HttpResponse<String> found = send("GET", "/api/me/favorite-groups", null, bearer);

        JsonNode groups = json.readTree(found.body()).path("groups");
        assertSoftly(softly -> {
            softly.assertThat(found.statusCode()).isEqualTo(200);
            softly.assertThat(groups.size()).isEqualTo(1);
            softly.assertThat(groups.get(0).path("name").asString()).isEqualTo("출퇴근");
            softly.assertThat(groups.get(0).path("favorites").isEmpty()).isTrue();
        });
    }

    @Test
    void 세_개가_있으면_새_그룹을_409로_거절한다() throws Exception {
        send("POST", "/api/me/favorite-groups", "{\"name\":\"출근\"}", bearer);
        send("POST", "/api/me/favorite-groups", "{\"name\":\"퇴근\"}", bearer);
        send("POST", "/api/me/favorite-groups", "{\"name\":\"주말\"}", bearer);

        HttpResponse<String> response = send("POST", "/api/me/favorite-groups", "{\"name\":\"추가\"}", bearer);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asString())
            .isEqualTo("FAVORITE_GROUP_LIMIT_EXCEEDED");
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        HttpResponse<String> response = send("GET", "/api/me/favorite-groups", null, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("UNAUTHENTICATED");
    }

    private HttpResponse<String> send(String method, String path, String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
