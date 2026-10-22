package com.gustler.backend.api.favorite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.favorite.application.FavoriteOverview.Stop;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(Include.NON_NULL)
@Schema(requiredProperties = "stopId")
public record FavoriteStopResponse(
    String stopId,
    String name,
    BoardDirection direction
) {
    static FavoriteStopResponse from(String stopId, Stop stop) {
        return new FavoriteStopResponse(stopId, stop == null ? null : stop.name(),
            stop == null ? null : stop.direction());
    }
}
