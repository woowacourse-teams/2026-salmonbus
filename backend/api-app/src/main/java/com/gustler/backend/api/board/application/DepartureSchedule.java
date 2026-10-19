package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.RouteHeadways;
import java.util.Objects;

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
