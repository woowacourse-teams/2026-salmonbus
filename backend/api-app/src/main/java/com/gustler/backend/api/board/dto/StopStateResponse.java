package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.StopState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 판본 안의 한 정류장 방문과 접근 차량을 공개 응답으로 전달한다.
 * 같은 ID의 반복 방문은 순번으로 남긴다.
 */
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
    /** 원본 참조와 분리하고 기존 null·원소·순서를 보존한다. */
    public StopStateResponse {
        if (approachingVehicles != null) {
            approachingVehicles = Collections.unmodifiableList(new ArrayList<>(approachingVehicles));
        }
    }

    static StopStateResponse from(
        StopState stop
    ) {
        return new StopStateResponse(
            stop.sequence(),
            stop.stopId(),
            stop.name(),
            stop.direction(),
            stop.boardingAllowed(),
            stop.approachingVehicles()
                .stream()
                .map(ApproachingVehicleResponse::from)
                .toList()
        );
    }
}
