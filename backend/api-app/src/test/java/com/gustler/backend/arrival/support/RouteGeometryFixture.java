package com.gustler.backend.arrival.support;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;

/** 공개 GBIS 저장 응답 또는 명시적으로 생성한 테스트 형상. */
public record RouteGeometryFixture(String routeId, String name, String startPoint, String endPoint,
                                   int turnSequence, List<Stop> stops, List<Point> points, JsonNode capturedResponseFields) {
    public record Stop(int sequence, String stationId, String name, double x, double y) { }
    public record Point(double x, double y) { }

    public static RouteGeometryFixture synthetic3330() {
        var points = IntStream.range(0, 836)
            .mapToObj(index -> new Point(127 + index * .00001, 37 + Math.sin(index * .01) * .001)).toList();
        var stops = IntStream.range(0, FakeGbisArrivalServer.STATIONS.size()).mapToObj(index -> {
            var point = points.get(index * 70);
            return new Stop(index + 1, FakeGbisArrivalServer.STATIONS.get(index), "synthetic stop", point.x(), point.y());
        }).toList();
        return new RouteGeometryFixture("204000057", "3330", "A", "B", 12, stops, points, null);
    }

    /** 실제 수신한 공개 정적 필드로 HTTP 수집·저장·응답 흐름을 재생한다. */
    public static RouteGeometryFixture fromCaptured(JsonNode route) {
        var stops = new ArrayList<Stop>();
        for (var stop : route.path("stops")) stops.add(new Stop(stop.path("stationSeq").intValue(),
            stop.path("stationId").asString(), stop.path("stationName").asString(),
            stop.path("x").doubleValue(), stop.path("y").doubleValue()));
        var points = new ArrayList<Point>();
        for (var point : route.path("shape")) points.add(new Point(point.path("x").doubleValue(), point.path("y").doubleValue()));
        var info = route.path("info").get(0);
        return new RouteGeometryFixture(route.path("routeId").asString(), info.path("routeName").asString(),
            info.path("startStationName").asString(), info.path("endStationName").asString(),
            route.path("stops").get(0).path("turnSeq").intValue(), List.copyOf(stops), List.copyOf(points), route);
    }
}
