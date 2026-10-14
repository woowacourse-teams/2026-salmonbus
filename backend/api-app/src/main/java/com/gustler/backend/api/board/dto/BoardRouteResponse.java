package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.route.domain.RouteStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"id", "displayName", "startStopName", "endStopName", "status",
    "turnSequence", "directions", "referenceVersionId"})
public record BoardRouteResponse(
    @Schema(description = "GBIS 노선 ID. 9자리 숫자 문자열")
    String id,
    @Schema(description = "화면에 표시할 노선 이름")
    String displayName,
    @Schema(description = "기점 이름")
    String startStopName,
    @Schema(description = "종점 이름")
    String endStopName,
    @Schema(description = "현재 판본의 예보 준비 상태")
    RouteStatus status,
    @Schema(description = "회차 정류장 순번. 회차점이 없으면 null", nullable = true)
    Integer turnSequence,
    @Schema(description = "상행/하행 방향 이름과 운행 시간")
    List<DirectionInfoResponse> directions,
    @Schema(description = "이 응답이 기준으로 삼은 노선 판본 ID")
    String referenceVersionId
) {

    static BoardRouteResponse from(
        BoardRoute route
    ) {
        return new BoardRouteResponse(
            route.id(),
            route.displayName(),
            route.startStopName(),
            route.endStopName(),
            route.status(),
            route.turnSequence(),
            route.directions().stream()
                .map(DirectionInfoResponse::from)
                .toList(),
            route.referenceVersionId()
        );
    }
}
