package com.gustler.backend.api.route.domain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 노선 판본의 방문 목록과 별도 형상 판본을 조합한 정적 지도 자료. */
public record RouteGeometry(String routeId, long referenceVersionId, String name, String startPoint,
                            String endPoint, Integer turnSequence, List<Stop> busStops, Shape shape) {
    public static final String COORDINATE_SYSTEM = "WGS84_ASSUMED";
    public enum Availability { READY, PARTIAL, UNAVAILABLE }

    public RouteGeometry { busStops = List.copyOf(busStops); }

    public record Point(double x, double y) {
        public Point {
            if (!valid(x, y)) throw new IllegalArgumentException("표시 가능한 좌표 범위를 벗어났다");
        }
        private static boolean valid(double x, double y) {
            return Double.isFinite(x) && Double.isFinite(y) && x != 0 && y != 0
                && x >= -180 && x <= 180 && y >= -90 && y <= 90;
        }
        public static Point orNull(Double x, Double y) {
            return x == null || y == null || !valid(x, y) ? null : new Point(x, y);
        }
    }
    public record Stop(String stationId, int stopSequence, String name, String direction,
                       boolean boardingAllowed, Point point) { }
    public record Shape(UUID id, Instant receivedAt, List<Point> points) {
        public Shape {
            points = List.copyOf(points);
            if (points.size() < 2 || points.stream().distinct().limit(2).count() < 2) {
                throw new IllegalArgumentException("유효한 형상이 아니다");
            }
        }
    }
    public record Boundary(double left, double top, double right, double bottom) { }

    public List<Point> points() { return shape == null ? List.of() : shape.points(); }
    public RoutePaths paths() { return RoutePathSplitter.split(this); }
    public Availability status() {
        if (shape == null) return Availability.UNAVAILABLE;
        return busStops.stream().anyMatch(stop -> stop.point() == null) ? Availability.PARTIAL : Availability.READY;
    }
    public String reason() {
        if (shape == null) return "NOT_COLLECTED";
        return status() == Availability.PARTIAL ? "MISSING_STOP_COORDINATES" : null;
    }
    public Boundary boundary() {
        List<Point> all = new ArrayList<>(points());
        busStops.stream().map(Stop::point).filter(java.util.Objects::nonNull).forEach(all::add);
        if (all.isEmpty()) return null;
        return new Boundary(all.stream().mapToDouble(Point::x).min().orElseThrow(),
            all.stream().mapToDouble(Point::y).max().orElseThrow(),
            all.stream().mapToDouble(Point::x).max().orElseThrow(),
            all.stream().mapToDouble(Point::y).min().orElseThrow());
    }

    /** 정류장 좌표만 바뀌어 referenceVersionId가 같아도 캐시 판본은 달라진다. */
    public String revision() {
        try {
            var bytes = new ByteArrayOutputStream();
            var data = new DataOutputStream(bytes);
            data.writeUTF("route-geometry-v2/" + COORDINATE_SYSTEM + "/" + RoutePathSplitter.REVISION);
            data.writeUTF(routeId); data.writeLong(referenceVersionId);
            data.writeUTF(name); data.writeUTF(startPoint); data.writeUTF(endPoint);
            data.writeInt(turnSequence == null ? -1 : turnSequence);
            data.writeInt(busStops.size());
            for (Stop stop : busStops) {
                data.writeUTF(stop.stationId()); data.writeInt(stop.stopSequence()); data.writeUTF(stop.name());
                data.writeUTF(stop.direction()); data.writeBoolean(stop.boardingAllowed());
                data.writeBoolean(stop.point() != null);
                if (stop.point() != null) { data.writeDouble(stop.point().x()); data.writeDouble(stop.point().y()); }
            }
            data.writeBoolean(shape != null);
            if (shape != null) { data.writeUTF(shape.id().toString()); data.writeUTF(shape.receivedAt().toString()); }
            for (Point point : points()) { data.writeDouble(point.x()); data.writeDouble(point.y()); }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
