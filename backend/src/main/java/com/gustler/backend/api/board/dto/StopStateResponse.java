package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.StopState;
import java.util.List;

public record StopStateResponse(
    int sequence,
    String stopId,
    String name,
    BoardDirection direction,
    boolean boardingAllowed,
    List<ApproachingVehicleResponse> approachingVehicles
) {

    static StopStateResponse from(final StopState stop) {
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
