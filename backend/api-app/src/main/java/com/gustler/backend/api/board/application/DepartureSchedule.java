package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.RouteHeadways;
import java.util.Objects;

/**
 * 방향별 첫차·막차 표시값이다.
 * 원천의 시각 표기를 조회 응답 구성에 전달한다.
 */
public record DepartureSchedule(
    String upFirstDepartureTime,
    String upLastDepartureTime,
    String downFirstDepartureTime,
    String downLastDepartureTime,
    RouteHeadways headways
) {
    public DepartureSchedule {
        Objects.requireNonNull(headways, "headways는 null일 수 없습니다.");
    }
}
