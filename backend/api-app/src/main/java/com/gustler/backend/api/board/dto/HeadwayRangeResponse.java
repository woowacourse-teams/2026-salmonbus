package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.HeadwayRange;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"minMinutes", "maxMinutes"})
public record HeadwayRangeResponse(
    @Schema(description = "최소 배차간격(분)", minimum = "1", maximum = "1440")
    int minMinutes,
    @Schema(description = "최대 배차간격(분). minMinutes와 같거나 크다", minimum = "1", maximum = "1440")
    int maxMinutes
) {

    static HeadwayRangeResponse from(
        HeadwayRange headway
    ) {
        if (headway == null) {
            return null;
        }
        return new HeadwayRangeResponse(headway.minMinutes(), headway.maxMinutes());
    }
}
