package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.Board;
import java.time.OffsetDateTime;
import java.util.List;

public record BoardResponse(
    BoardRouteResponse route,
    OffsetDateTime observedAt,
    OffsetDateTime staleAt,
    ModelInfoResponse model,
    int vehiclesInService,
    List<StopStateResponse> stops
) {

    public static BoardResponse from(
        Board board
    ) {
        return new BoardResponse(
            BoardRouteResponse.from(board.route()),
            board.observedAt(),
            board.staleAt(),
            ModelInfoResponse.from(board.model()),
            board.vehiclesInService(),
            board.stops().stream()
                .map(StopStateResponse::from)
                .toList()
        );
    }
}
