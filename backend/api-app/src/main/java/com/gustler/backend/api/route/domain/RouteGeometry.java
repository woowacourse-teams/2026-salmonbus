package com.gustler.backend.api.route.domain;

import com.gustler.backend.api.route.domain.RouteGeometry.Shape;
import com.gustler.backend.api.route.domain.RouteGeometry.Stop;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 노선 판본의 방문 목록과 별도 형상 판본을 조합한 정적 지도 자료. */
public record RouteGeometry(String routeId, long referenceVersionId, String name, String startPoint,
    String endPoint, Integer turnSequence, List<Stop> busStops, Shape shape) {
    public static final String COORDINATE_SYSTEM = "WGS84_ASSUMED";
    public enum Availability {
        READY, PARTIAL, UNAVAILABLE
    }

    public RouteGeometry {
        busStops = List.copyOf(busStops);
    }

    public record Point(double x, double y) {
        public Point {
            if (!valid(x, y)) {
                throw new IllegalArgumentException("표시 가능한 좌표 범위를 벗어났다");
            }
        }

        private static boolean valid(final double x, final double y) {
            return Double.isFinite(x) && Double.isFinite(y) && x != 0 && y != 0
                && x >= -180 && x <= 180 && y >= -90 && y <= 90;
        }

        public static Point orNull(Double x, Double y) {
            if (x == null || y == null) {
                return null;
            }
            if (!valid(x, y)) {
                return null;
            }
            return new Point(x, y);
        }
    }
    public record Stop(String stationId, int stopSequence, String name, String direction,
        boolean boardingAllowed, Point point) {
    }
    public record Shape(UUID id, Instant receivedAt, List<Point> points) {
        public Shape {
            points = List.copyOf(points);
            if (points.size() < 2 || points.stream()
                .distinct()
                .limit(2)
                .count() < 2) {
                throw new IllegalArgumentException("유효한 형상이 아니다");
            }
        }
    }
    public record Boundary(double left, double top, double right, double bottom) {
    }

    public List<Point> points() {
        if (shape == null) {
            return List.of();
        }
        return shape.points();
    }

    public RoutePaths paths() {
        return RoutePathSplitter.split(this);
    }

    public Availability status() {
        if (shape == null) {
            return Availability.UNAVAILABLE;
        }
        if (busStops.stream().anyMatch(stop -> stop.point() == null)) {
            return Availability.PARTIAL;
        }
        return Availability.READY;
    }

    public String reason() {
        if (shape == null) {
            return "NOT_COLLECTED";
        }
        if (status() == Availability.PARTIAL) {
            return "MISSING_STOP_COORDINATES";
        }
        return null;
    }

    public Boundary boundary() {
        List<Point> all = new ArrayList<>(points());
        busStops.stream()
            .map(Stop::point)
            .filter(Objects::nonNull)
            .forEach(all::add);
        if (all.isEmpty()) {
            return null;
        }
        return new Boundary(all.stream()
            .mapToDouble(Point::x)
            .min()
            .orElseThrow(),
            all.stream()
                .mapToDouble(Point::y)
                .max()
                .orElseThrow(),
            all.stream()
                .mapToDouble(Point::x)
                .max()
                .orElseThrow(),
            all.stream()
                .mapToDouble(Point::y)
                .min()
                .orElseThrow());
    }

    /** 정류장 좌표만 바뀌어 referenceVersionId가 같아도 캐시 판본은 달라진다. */
    public String revision() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream revisionOutput = new DataOutputStream(bytes);
            revisionOutput.writeUTF("route-geometry-v2/" + COORDINATE_SYSTEM + "/" + RoutePathSplitter.REVISION);
            revisionOutput.writeUTF(routeId);
            revisionOutput.writeLong(referenceVersionId);
            revisionOutput.writeUTF(name);
            revisionOutput.writeUTF(startPoint);
            revisionOutput.writeUTF(endPoint);
            revisionOutput.writeInt(encodedTurnSequence());
            revisionOutput.writeInt(busStops.size());
            for (Stop stop : busStops) {
                Point stopPoint = stop.point();
                revisionOutput.writeUTF(stop.stationId());
                revisionOutput.writeInt(stop.stopSequence());
                revisionOutput.writeUTF(stop.name());
                revisionOutput.writeUTF(stop.direction());
                revisionOutput.writeBoolean(stop.boardingAllowed());
                revisionOutput.writeBoolean(stopPoint != null);
                if (stopPoint != null) {
                    revisionOutput.writeDouble(stopPoint.x());
                    revisionOutput.writeDouble(stopPoint.y());
                }
            }
            revisionOutput.writeBoolean(shape != null);
            if (shape != null) {
                revisionOutput.writeUTF(shape.id().toString());
                revisionOutput.writeUTF(shape.receivedAt().toString());
            }
            for (Point point : points()) {
                revisionOutput.writeDouble(point.x());
                revisionOutput.writeDouble(point.y());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 해시 입력의 부재 표기는 기존 -1을 유지한다. 공개 DTO의 null 표현과 구분한다. */
    private int encodedTurnSequence() {
        if (turnSequence == null) {
            return -1;
        }
        return turnSequence;
    }
}
