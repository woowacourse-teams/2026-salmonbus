package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.DirectionInfo;

public record DirectionInfoResponse(
    BoardDirection id,
    String name,
    String originStopName,
    String terminalStopName,
    String firstDepartureTime,
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
