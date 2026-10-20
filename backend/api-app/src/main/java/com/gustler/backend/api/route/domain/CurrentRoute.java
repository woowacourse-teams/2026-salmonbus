package com.gustler.backend.api.route.domain;

import java.util.Objects;

/**
 * 현재 공개 노선의 식별·표시·예보 준비 상태를 목록 조회에 전달한다.
 */
public record CurrentRoute(
    Route route,
    RouteStatus status
) {
    public CurrentRoute {
        Objects.requireNonNull(route, "route는 null일 수 없습니다.");
        Objects.requireNonNull(status, "status는 null일 수 없습니다.");
    }
}
