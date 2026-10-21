package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.domain.CurrentRoute;
import java.util.List;
import java.util.Objects;

/**
 * 공개 노선 목록과 목록 조회 결과의 준비 상태를 전달한다.
 */
public record RouteOverview(
    List<CurrentRoute> routes
) {
    public RouteOverview {
        Objects.requireNonNull(routes, "routes는 null일 수 없습니다.");
        routes = List.copyOf(routes);
    }
}
