package com.gustler.backend.api.board.domain;

public record RouteHeadways(
    HeadwayRange weekday,
    HeadwayRange saturday,
    HeadwayRange sunday,
    HeadwayRange holiday
) {

    public static RouteHeadways none() {
        return new RouteHeadways(null, null, null, null);
    }
}
