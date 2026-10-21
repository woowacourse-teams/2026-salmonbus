package com.gustler.backend.forecasting.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 학습에 사용한 정류장 기준. 같은 노선 번호라도 정류장이 달라지면 계산하지 않는다. */
public final class ForecastRouteReference {
    public record Stop(int order, String id, boolean boardingAllowed, RouteDirection direction) {
        public Stop {
            if (!isValid(order, id, direction)) {
                throw new IllegalArgumentException("학습 정류장에는 순번·ID·UP/DOWN이 필요하다");
            }
        }

        private static boolean isValid(int order, String id, RouteDirection direction) {
            if (order < 1 || id == null) {
                return false;
            }
            if (id.isBlank() || direction == null) {
                return false;
            }
            return direction != RouteDirection.UNKNOWN;
        }
    }

    private final Map<String, List<Stop>> routes;
    private final Map<Long, RouteStops> checked = new ConcurrentHashMap<>();

    public ForecastRouteReference(Map<String, List<Stop>> routes) {
        this.routes = routes.entrySet()
            .stream()
            .collect(Collectors.toUnmodifiableMap(
                Entry::getKey, entry -> List.copyOf(entry.getValue())));
        for (List<Stop> stops : this.routes.values()) {
            int previous = 0;
            for (Stop stop : stops) {
                if (stop.order() <= previous) {
                    throw new IllegalArgumentException("학습 정류장 순번은 중복 없이 오름차순이어야 한다");
                }
                previous = stop.order();
            }
            if (stops.isEmpty()) {
                throw new IllegalArgumentException("학습 정류장 목록은 비어 있을 수 없다");
            }
        }
    }

    public boolean matches(RouteStops actual) {
        if (checked.get(actual.routeVersionId()) == actual) {
            return true;
        }
        if (!ModelRoute.covers(actual.sourceRouteId())) {
            return false;
        }
        List<Stop> expected = routes.get(ModelRoute.of(actual.sourceRouteId()));
        if (expected == null || expected.size() != actual.stops().size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            Stop trained = expected.get(index);
            RouteStop current = actual.stops().get(index);
            if (!matches(trained, current)) {
                return false;
            }
        }
        checked.put(actual.routeVersionId(), actual);
        return true;
    }

    private boolean matches(Stop trained, RouteStop current) {
        if (trained.order() != current.stopOrder()) {
            return false;
        }
        if (!trained.id().equals(current.stopId())) {
            return false;
        }
        if (trained.boardingAllowed() != current.boardingAllowed()) {
            return false;
        }
        return trained.direction() == current.direction();
    }

    public void requireMatches(RouteStops actual) {
        if (!matches(actual)) {
            throw new IllegalArgumentException("학습 정류장 기준과 운영 노선이 다르다: " + actual.sourceRouteId());
        }
    }
}
