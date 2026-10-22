package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.HeadwayRange;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 분 단위의 최소·최대 배차간격을 공개 응답으로 전달한다.
 * 실제 도착 간격이나 평균값을 뜻하지 않는다.
 */
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
