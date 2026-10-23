package com.gustler.backend.api.favorite.dto;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.gustler.backend.api.favorite.application.FavoriteOverview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@JsonInclude(Include.NON_NULL)
@Schema(requiredProperties = {"id", "status", "route", "stop", "approachingVehicles"})
public record FavoriteItemResponse(
    UUID id,
    @Schema(description = "노선 정류장 항목 상태")
    FavoriteOverview.ItemStatus status,
    @Schema(description = "항목을 조회할 수 없는 이유. READY, NO_VEHICLE이면 생략")
    FavoriteOverview.ItemReason reason,
    FavoriteBoardRouteResponse route,
    FavoriteStopResponse stop,
    @Schema(description = "watch와 같은 접근 차량 구조에 ETA만 추가한 목록")
    List<FavoriteApproachingVehicleResponse> approachingVehicles
) {
    public FavoriteItemResponse {
        approachingVehicles = List.copyOf(approachingVehicles);
    }

    static FavoriteItemResponse from(FavoriteOverview.Item item) {
        FavoriteOverview.BoardSlice board = item.board();
        if (board == null) {
            return unavailable(item);
        }
        List<FavoriteApproachingVehicleResponse> vehicles = board.stop().approachingVehicles().stream()
            .map(FavoriteApproachingVehicleResponse::from)
            .toList();
        return new FavoriteItemResponse(item.id(), item.status(), item.reason(),
            FavoriteBoardRouteResponse.from(item.target().routeId(), item.target().direction(), board.route()),
            FavoriteStopResponse.from(item.target().stopId(), board.stop()), vehicles);
    }

    private static FavoriteItemResponse unavailable(FavoriteOverview.Item item) {
        return new FavoriteItemResponse(
            item.id(),
            item.status(),
            item.reason(),
            FavoriteBoardRouteResponse.from(item.target().routeId(), item.target().direction(), null),
            FavoriteStopResponse.from(item.target().stopId(), null),
            List.of()
        );
    }
}
