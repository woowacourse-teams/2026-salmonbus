package com.gustler.backend.api.favorite.dto;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(Include.NON_NULL)
@Schema(requiredProperties = "id")
public record FavoriteBoardRouteResponse(
    @Schema(description = "watch와 같은 9자리 GBIS 노선 ID")
    String id,
    @Schema(description = "watch와 같은 화면 표시용 노선 이름")
    String displayName,
    @Schema(
        description = "선택한 방향의 종점 정류장 이름. 화면에서 방면 문구를 조합할 때 사용"
    )
    String terminalStopName
) {
    static FavoriteBoardRouteResponse from(String routeId, FavoriteDirection direction, BoardRoute route) {
        if (route == null) {
            return new FavoriteBoardRouteResponse(routeId, null, null);
        }
        String terminalStopName = route.directions().stream()
            .filter(candidate -> candidate.id().name().equals(direction.name()))
            .map(candidate -> candidate.terminalStopName())
            .findFirst()
            .orElse(null);
        return new FavoriteBoardRouteResponse(routeId, route.displayName(), terminalStopName);
    }
}
