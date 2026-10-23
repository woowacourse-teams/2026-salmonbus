package com.gustler.backend.api.favorite.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.DirectionInfo;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.favorite.application.FavoriteOverview;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FavoriteOverviewResponseTest {
    private static final FavoriteTarget TARGET = new FavoriteTarget("204000057", "277102322", FavoriteDirection.UP);
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void watch_필드_이름을_유지하고_ETA를_추가한다() {
        BoardRoute route = new BoardRoute(TARGET.routeId(), "M5422", "기점", "종점",
            RouteStatus.FORECAST_READY, null,
            List.of(new DirectionInfo(BoardDirection.UP, "안양역 방면", "기점", "안양역", "05:00", "23:00")),
            "12", RouteHeadways.none());
        ApproachingVehicle watch = new ApproachingVehicle("vehicle-1", 2,
            new VehicleForecast.Available(0.8, 12.0));
        FavoriteOverview.Stop stop = new FavoriteOverview.Stop(
            TARGET.stopId(),
            "강남역",
            BoardDirection.UP,
            List.of(new FavoriteOverview.Vehicle(
                watch,
                FavoriteOverview.Eta.unavailable(FavoriteOverview.EtaReason.ARRIVAL_UNAVAILABLE)
            ))
        );
        FavoriteOverview.Item item = new FavoriteOverview.Item(
            UUID.randomUUID(),
            TARGET,
            FavoriteOverview.ItemStatus.READY,
            null,
            new FavoriteOverview.BoardSlice(route, stop)
        );

        JsonNode body = json.valueToTree(FavoriteOverviewResponse.from(
            new FavoriteOverview(List.of(new FavoriteOverview.Group(
                UUID.randomUUID(),
                "출근",
                List.of(item)
            )))
        ));
        JsonNode actual = body.at("/groups/0/favorites/0");

        assertThat(actual.at("/route/id").asString()).isEqualTo(TARGET.routeId());
        assertThat(actual.at("/route/displayName").asString()).isEqualTo("M5422");
        assertThat(actual.at("/route/terminalStopName").asString()).isEqualTo("안양역");
        assertThat(actual.at("/stop/stopId").asString()).isEqualTo(TARGET.stopId());
        assertThat(actual.at("/approachingVehicles/0/horizonStops").asInt()).isEqualTo(2);
        assertThat(actual.at("/approachingVehicles/0/forecast/status").asString())
            .isEqualTo("AVAILABLE");
        assertThat(actual.at("/approachingVehicles/0/eta/status").asString())
            .isEqualTo("UNAVAILABLE");
        assertThat(actual.has("target")).isFalse();
        assertThat(actual.has("board")).isFalse();
        assertThat(actual.has("remainingStops")).isFalse();
    }

    @Test
    void 판정보드를_읽지_못한_항목도_저장_대상과_상태를_남긴다() {
        FavoriteOverview.Item item = new FavoriteOverview.Item(
            UUID.randomUUID(),
            TARGET,
            FavoriteOverview.ItemStatus.UNAVAILABLE,
            FavoriteOverview.ItemReason.OBSERVATION_UNAVAILABLE,
            null
        );

        JsonNode body = json.valueToTree(FavoriteOverviewResponse.from(
            new FavoriteOverview(List.of(new FavoriteOverview.Group(
                UUID.randomUUID(),
                "출근",
                List.of(item)
            )))
        ));
        JsonNode actual = body.at("/groups/0/favorites/0");

        assertThat(actual.at("/route/id").asString()).isEqualTo(TARGET.routeId());
        assertThat(actual.at("/stop/stopId").asString()).isEqualTo(TARGET.stopId());
        assertThat(actual.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(actual.path("reason").asString()).isEqualTo("OBSERVATION_UNAVAILABLE");
        assertThat(actual.path("approachingVehicles").isEmpty()).isTrue();
        assertThat(actual.has("board")).isFalse();
    }
}
