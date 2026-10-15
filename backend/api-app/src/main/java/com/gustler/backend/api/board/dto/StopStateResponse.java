package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.StopState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"sequence", "stopId", "name", "direction",
    "boardingAllowed", "approachingVehicles"})
public record StopStateResponse(
    @Schema(description = "현재 노선 판본에서의 정류장 순번")
    int sequence,
    @Schema(description = "정류장 ID")
    String stopId,
    @Schema(description = "정류장 이름")
    String name,
    @Schema(description = "UP 또는 DOWN")
    BoardDirection direction,
    @Schema(description = "이 정류장에서 승차 가능 여부")
    boolean boardingAllowed,
    @Schema(description = "접근 차량 목록. 없으면 빈 배열")
    List<ApproachingVehicleResponse> approachingVehicles
) {

    static StopStateResponse from(
        StopState stop
    ) {
        return new StopStateResponse(
            stop.sequence(),
            stop.stopId(),
            stop.name(),
            stop.direction(),
            stop.boardingAllowed(),
            stop.approachingVehicles().stream()
                .map(ApproachingVehicleResponse::from)
                .toList()
        );
    }
}
