package com.gustler.backend.api.favorite.controller;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.ForecastBoardFixture;
import com.gustler.backend.api.favorite.application.FavoriteGroupRepository;
import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.FavoriteGroupName;
import com.gustler.backend.api.favorite.domain.FavoriteId;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import com.gustler.backend.api.http.login.LoginIntegrationTest;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SocialIdentity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 즐겨찾기 조회가 예보 기록에서 읽은 예측을 접근 차량에 그대로 싣는지 실제 톰캣과 실제 DB로 검증한다.
 *
 * <p>시계가 실제 시각이라 관측 시각을 지금 기준으로 넣는다. 컨텍스트를 함께 쓰고 롤백하지 않으므로 넣은 줄은 시험마다 비운다.
 */
@LoginIntegrationTest
class FavoriteOverviewForecastIntegrationTest {

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private Clock clock;

    @Autowired
    private SignInWithSocialAccount signIn;

    @Autowired
    private AccessTokens accessTokens;

    @Autowired
    private FavoriteGroupRepository favoriteGroups;

    private ForecastBoardFixture fixture;
    private OffsetDateTime now;
    private String bearer;

    @BeforeEach
    void 회원과_즐겨찾기를_만든다() {
        fixture = new ForecastBoardFixture(jdbcClient, objectMapper);
        fixture.clear();
        jdbcClient.sql("DELETE FROM member").update();
        UUID memberId = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "favorite-forecast")).publicId();
        favoriteGroups.update(memberId, ignored -> new MemberFavoriteGroups(List.of(favoriteGroup())));
        bearer = "Bearer " + accessTokens.issue(memberId, null);
        now = OffsetDateTime.now(clock).withNano(0);
    }

    @AfterEach
    void 넣은_줄을_비운다() {
        fixture.clear();
    }

    @Test
    void 예측이_있는_접근_차량은_넣은_확률과_기대_좌석을_그대로_싣는다() throws Exception {
        // given
        BoardDatabaseFixture.RouteContext route = fixture.insertRoute(now.minusDays(1));
        final long modelId = fixture.insertActiveModel(now.minusDays(1));
        ForecastBoardFixture.ObservedVehicle vehicle = fixture.insertApproachingVehicle(route, now.minusMinutes(1));
        fixture.insertForecast(
                vehicle,
                modelId,
                0.25,
                12.5
        );

        // when
        HttpResponse<String> actual = getFavorites();

        // then
        JsonNode favorite = onlyFavoriteOf(actual);
        JsonNode approaching = favorite.path("approachingVehicles");
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(favorite.path("status").asString()).isEqualTo("READY");
            softly.assertThat(favorite.has("reason")).isFalse();
            softly.assertThat(approaching.size()).isEqualTo(1);
            softly.assertThat(approaching.path(0).path("vehicleId").asString())
                .isEqualTo(ForecastBoardFixture.VEHICLE_ID);
            softly.assertThat(approaching.path(0).path("remainingStops").asInt()).isEqualTo(1);
            softly.assertThat(approaching.path(0).path("forecast")).isEqualTo(objectMapper.readTree("""
                {
                  "status": "AVAILABLE",
                  "seatAvailableProbability": 0.75,
                  "expectedSeats": 12.5,
                  "seatTarget": "AFTER_ALIGHTING_ESTIMATE",
                  "probabilityTarget": "DEPARTURE_ZERO",
                  "afterAlightingSeatEstimate": 12.5,
                  "departureFullProbability": 0.25
                }
                """));
        });
    }

    @Test
    void 예측_없이_끝난_실행_줄이면_접근_차량의_예보는_UNAVAILABLE이다() throws Exception {
        // given
        BoardDatabaseFixture.RouteContext route = fixture.insertRoute(now.minusDays(1));
        final long modelId = fixture.insertActiveModel(now.minusDays(1));
        ForecastBoardFixture.ObservedVehicle vehicle = fixture.insertApproachingVehicle(route, now.minusMinutes(1));
        fixture.insertRunWithoutForecast(vehicle, modelId, "OUT_OF_SCOPE");

        // when
        HttpResponse<String> actual = getFavorites();

        // then
        JsonNode favorite = onlyFavoriteOf(actual);
        JsonNode approaching = favorite.path("approachingVehicles");
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(favorite.path("status").asString()).isEqualTo("READY");
            softly.assertThat(approaching.size()).isEqualTo(1);
            softly.assertThat(approaching.path(0).path("vehicleId").asString())
                .isEqualTo(ForecastBoardFixture.VEHICLE_ID);
            softly.assertThat(approaching.path(0).path("forecast"))
                .isEqualTo(objectMapper.readTree("{\"status\": \"UNAVAILABLE\"}"));
        });
    }

    @Test
    void 활성_모델이_없으면_항목은_PREPARING이고_사유는_MODEL_NOT_READY다() throws Exception {
        // given
        BoardDatabaseFixture.RouteContext route = fixture.insertRoute(now.minusDays(1));
        fixture.insertApproachingVehicle(route, now.minusMinutes(1));

        // when
        HttpResponse<String> actual = getFavorites();

        // then
        JsonNode favorite = onlyFavoriteOf(actual);
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(favorite.path("status").asString()).isEqualTo("PREPARING");
            softly.assertThat(favorite.path("reason").asString()).isEqualTo("MODEL_NOT_READY");
            softly.assertThat(favorite.path("approachingVehicles").isEmpty()).isTrue();
        });
    }

    @Test
    void 최근_관측이_없으면_항목은_UNAVAILABLE이고_사유는_OBSERVATION_UNAVAILABLE이다() throws Exception {
        // given
        fixture.insertRoute(now.minusDays(1));
        fixture.insertActiveModel(now.minusDays(1));

        // when
        HttpResponse<String> actual = getFavorites();

        // then
        JsonNode favorite = onlyFavoriteOf(actual);
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(favorite.path("status").asString()).isEqualTo("UNAVAILABLE");
            softly.assertThat(favorite.path("reason").asString()).isEqualTo("OBSERVATION_UNAVAILABLE");
            softly.assertThat(favorite.path("approachingVehicles").isEmpty()).isTrue();
        });
    }

    private static FavoriteGroup favoriteGroup() {
        Favorite favorite = new Favorite(
            new FavoriteId(UUID.randomUUID()),
            new FavoriteTarget(ForecastBoardFixture.ROUTE_ID, ForecastBoardFixture.STOP_ID, FavoriteDirection.DOWN));
        return new FavoriteGroup(
            new FavoriteGroupId(UUID.randomUUID()),
            new FavoriteGroupName("출근"),
            List.of(favorite));
    }

    private HttpResponse<String> getFavorites() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me/favorites"))
            .header("Authorization", bearer)
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 응답의 하나뿐인 그룹에서 하나뿐인 즐겨찾기 항목을 꺼낸다. 없으면 빈 노드라 뒤따르는 단정이 실패한다.
     */
    private JsonNode onlyFavoriteOf(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).path("groups").path(0).path("favorites").path(0);
    }
}
