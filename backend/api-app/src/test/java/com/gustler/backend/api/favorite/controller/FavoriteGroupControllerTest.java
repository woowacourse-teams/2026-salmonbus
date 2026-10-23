package com.gustler.backend.api.favorite.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.favorite.application.FavoriteGroupRepository;
import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.FavoriteGroupName;
import com.gustler.backend.api.favorite.domain.FavoriteId;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.support.PostgresTestContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.SpringBootTest;
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
    @Autowired
    private FavoriteGroupRepository favoriteGroups;

    private UUID memberId;
    private String bearer;

    @BeforeEach
    void 회원을_만든다() {
        jdbc.sql("DELETE FROM member").update();
        memberId = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "favorite-member")).publicId();
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

    @Test
    void 그룹과_각_그룹의_즐겨찾기_순서를_한번에_저장한다() throws Exception {
        FavoriteGroup first = group("출근", "277102322", "277102323");
        FavoriteGroup second = group("퇴근", "277102324", "277102325");
        favoriteGroups.update(memberId, ignored -> new MemberFavoriteGroups(List.of(first, second)));
        String body = orderBody(List.of(
            order(second, second.favorites().reversed()),
            order(first, first.favorites().reversed())
        ));

        HttpResponse<String> response = send("PUT", "/api/me/favorite-order", body, bearer);

        MemberFavoriteGroups saved = favoriteGroups.findByMemberPublicId(memberId).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(204);
            softly.assertThat(saved.values()).extracting(group -> group.id().value())
                .containsExactly(second.id().value(), first.id().value());
            softly.assertThat(saved.values().get(0).favorites()).extracting(favorite -> favorite.id().value())
                .containsExactlyElementsOf(second.favorites().reversed().stream()
                    .map(favorite -> favorite.id().value()).toList());
            softly.assertThat(saved.values().get(1).favorites()).extracting(favorite -> favorite.id().value())
                .containsExactlyElementsOf(first.favorites().reversed().stream()
                    .map(favorite -> favorite.id().value()).toList());
        });
    }

    @Test
    void 한_그룹의_즐겨찾기_ID가_빠지면_모든_순서를_유지한다() throws Exception {
        FavoriteGroup first = group("출근", "277102322", "277102323");
        FavoriteGroup second = group("퇴근", "277102324", "277102325");
        MemberFavoriteGroups before = new MemberFavoriteGroups(List.of(first, second));
        favoriteGroups.update(memberId, ignored -> before);
        String body = orderBody(List.of(
            order(second, second.favorites().reversed()),
            order(first, List.of(first.favorites().getFirst()))
        ));

        HttpResponse<String> response = send("PUT", "/api/me/favorite-order", body, bearer);

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(400);
            softly.assertThat(json.readTree(response.body()).path("code").asString())
                .isEqualTo("INVALID_FAVORITE_ORDER");
            softly.assertThat(favoriteGroups.findByMemberPublicId(memberId).orElseThrow())
                .usingRecursiveComparison()
                .isEqualTo(before);
        });
    }

    private String orderBody(List<Map<String, Object>> groups) throws Exception {
        return json.writeValueAsString(Map.of("groups", groups));
    }

    private static Map<String, Object> order(FavoriteGroup group, List<Favorite> favorites) {
        return Map.of(
            "groupId", group.id().value(),
            "favoriteIds", favorites.stream().map(favorite -> favorite.id().value()).toList()
        );
    }

    private static FavoriteGroup group(String name, String firstStopId, String secondStopId) {
        return new FavoriteGroup(new FavoriteGroupId(UUID.randomUUID()), new FavoriteGroupName(name), List.of(
            favorite(firstStopId, FavoriteDirection.UP),
            favorite(secondStopId, FavoriteDirection.DOWN)
        ));
    }

    private static Favorite favorite(String stopId, FavoriteDirection direction) {
        return new Favorite(new FavoriteId(UUID.randomUUID()),
            new FavoriteTarget("204000057", stopId, direction));
    }

    private HttpResponse<String> send(String method, String path, String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
