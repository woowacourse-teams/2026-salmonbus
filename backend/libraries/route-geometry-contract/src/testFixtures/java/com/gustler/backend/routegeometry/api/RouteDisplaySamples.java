package com.gustler.backend.routegeometry.api;

import com.gustler.backend.routegeometry.api.RouteDisplayInput.Point;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 공개 의미 계약을 시험하는 작은 왕복 노선. 운영 승인 자료가 아니다. */
public final class RouteDisplaySamples {
    private RouteDisplaySamples() {}

    public static RouteDisplayInput input() {
        Point a = new Point(127, 37), b = new Point(127.01, 37.01);
        return new RouteDisplayInput(
                "204000057",
                2,
                List.of(a, b, a),
                List.of(
                        new RouteDisplayInput.Visit("206000637", 1, "UP", true, a),
                        new RouteDisplayInput.Visit("206000638", 2, "UP", true, b),
                        new RouteDisplayInput.Visit("206000637", 3, "DOWN", true, a)));
    }

    public static ManualRouteDisplay manual(RouteDisplayInput input, double offset) {
        Point a = moved(input.points().getFirst(), offset),
                b = moved(input.points().get(1), offset);
        Map<String, ManualRouteDisplay.Path> paths =
                Map.of(
                        "UP",
                        new ManualRouteDisplay.Path(List.of(a, b)),
                        "DOWN",
                        new ManualRouteDisplay.Path(List.of(b, a)));
        Map<String, ManualRouteDisplay.Connection> connections = new HashMap<>();
        var visits =
                input.visits().stream()
                        .map(
                                visit -> {
                                    if (visit.sourcePoint() != null) {
                                        double fraction = visit.sequence() == 1 ? 0 : 1;
                                        Point connection = visit.sequence() == 2 ? b : a;
                                        connections.put(
                                                Integer.toString(visit.sequence()),
                                                new ManualRouteDisplay.Connection(
                                                        connection, 0, fraction));
                                    }
                                    return new ManualRouteDisplay.Visit(
                                            visit.stationId(),
                                            visit.sequence(),
                                            visit.direction(),
                                            visit.boardingAllowed(),
                                            visit.sourcePoint(),
                                            visit.sourcePoint() == null
                                                    ? null
                                                    : moved(visit.sourcePoint(), offset));
                                })
                        .toList();
        return new ManualRouteDisplay(
                ManualRouteDisplay.FORMAT,
                "REVIEWED",
                "test-reviewed-v1",
                input.routeId(),
                input.digest(),
                paths,
                connections,
                visits,
                new ManualRouteDisplay.Review("test-operator", Instant.EPOCH));
    }

    private static Point moved(Point point, double offset) {
        return new Point(point.x() + offset, point.y());
    }
}
