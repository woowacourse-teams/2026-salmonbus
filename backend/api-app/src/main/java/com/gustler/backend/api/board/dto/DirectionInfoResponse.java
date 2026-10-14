package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.DirectionInfo;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"id", "name", "originStopName", "terminalStopName",
    "firstDepartureTime", "lastDepartureTime"})
public record DirectionInfoResponse(
    @Schema(description = "UP 또는 DOWN")
    BoardDirection id,
    @Schema(description = "화면에 표시할 방향 이름")
    String name,
    @Schema(description = "이 방향의 기점 정류장 이름")
    String originStopName,
    @Schema(description = "이 방향의 종점 정류장 이름")
    String terminalStopName,
    @Schema(description = "첫차 시각. HH:mm 형식")
    String firstDepartureTime,
    @Schema(description = "막차 시각. HH:mm 형식")
    String lastDepartureTime
) {

    static DirectionInfoResponse from(
        DirectionInfo direction
    ) {
        return new DirectionInfoResponse(
            direction.id(),
            direction.name(),
            direction.originStopName(),
            direction.terminalStopName(),
            direction.firstDepartureTime(),
            direction.lastDepartureTime()
        );
    }
}
