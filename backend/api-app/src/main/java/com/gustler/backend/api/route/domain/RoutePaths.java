package com.gustler.backend.api.route.domain;

import java.util.List;
import java.util.Objects;

/** 같은 형상을 운행 순서대로 나눈 두 방향. 회차 경계점은 양쪽이 공유한다. */
public record RoutePaths(Path up, Path down) {
    public RoutePaths {
        Objects.requireNonNull(up);
        Objects.requireNonNull(down);
    }

    public record Path(List<RouteGeometry.Point> points) {
        public Path {
            points = List.copyOf(points);
            if (points.stream().distinct().limit(2).count() < 2) {
                throw new IllegalArgumentException("방향별 경로에는 서로 다른 좌표가 두 개 이상 필요하다");
            }
        }
    }
}
