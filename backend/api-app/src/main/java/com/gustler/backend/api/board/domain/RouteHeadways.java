package com.gustler.backend.api.board.domain;

/**
 * 요일별 최소·최대 배차간격을 보관한다.
 * 정보 부재·미해석 상태와 마지막 정상 값의 유지 여부를 구분한다.
 */
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
