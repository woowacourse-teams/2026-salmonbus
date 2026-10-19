package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.RouteHeadways;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"weekday", "saturday", "sunday", "holiday"})
public record RouteHeadwaysResponse(
    @Schema(description = "평일 배차간격. 정보가 없으면 null", nullable = true)
    HeadwayRangeResponse weekday,
    @Schema(description = "토요일 배차간격. 정보가 없으면 null", nullable = true)
    HeadwayRangeResponse saturday,
    @Schema(description = "일요일 배차간격. 정보가 없으면 null", nullable = true)
    HeadwayRangeResponse sunday,
    @Schema(description = "공휴일 배차간격. 정보가 없으면 null이며 일요일 값으로 대신하지 않는다",
        nullable = true)
    HeadwayRangeResponse holiday
) {

    static RouteHeadwaysResponse from(
        RouteHeadways headways
    ) {
        return new RouteHeadwaysResponse(
            HeadwayRangeResponse.from(headways.weekday()),
            HeadwayRangeResponse.from(headways.saturday()),
            HeadwayRangeResponse.from(headways.sunday()),
            HeadwayRangeResponse.from(headways.holiday())
        );
    }
}
