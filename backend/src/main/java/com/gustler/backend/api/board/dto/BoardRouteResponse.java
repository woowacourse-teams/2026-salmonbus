package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.List;

public record BoardRouteResponse(
    String id,
    String displayName,
    String startStopName,
    String endStopName,
    RouteStatus status,
    Integer turnSequence,
    List<DirectionInfoResponse> directions,
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
