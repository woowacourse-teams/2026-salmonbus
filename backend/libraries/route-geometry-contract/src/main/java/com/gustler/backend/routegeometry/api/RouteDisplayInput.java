package com.gustler.backend.routegeometry.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** 표시 형상 적용 가능성을 판단하는 원본 입력. 이름·DB 판본·수집 시각은 포함하지 않는다. */
public record RouteDisplayInput(
        String routeId, Integer turnSequence, List<Point> points, List<Visit> visits) {
    public RouteDisplayInput {
        if (routeId == null || !routeId.matches("[0-9]{9}")) {
            throw new IllegalArgumentException("9자리 GBIS 노선 ID가 필요하다");
        }
        points = List.copyOf(points);
        visits = List.copyOf(visits);
        if (points.size() < 2
                || points.size() > 100_000
                || points.stream().distinct().limit(2).count() < 2
                || visits.isEmpty()
                || visits.stream().map(Visit::sequence).distinct().count() != visits.size()) {
            throw new IllegalArgumentException("원본 형상과 중복 없는 방문 순번이 필요하다");
        }
        int previous = 0;
        for (Visit visit : visits) {
            if (visit.sequence() <= previous) {
                throw new IllegalArgumentException("원본 방문 순서가 필요하다");
            }
            previous = visit.sequence();
        }
    }

    public record Point(double x, double y) {
        public Point {
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || x == 0
                    || y == 0
                    || x < -180
                    || x > 180
                    || y < -90
                    || y > 90) {
                throw new IllegalArgumentException("유효한 원본 좌표가 필요하다");
            }
        }
    }

    public record Visit(
            String stationId,
            int sequence,
            String direction,
            boolean boardingAllowed,
            Point sourcePoint) {
        public Visit {
            if (stationId == null
                    || stationId.isBlank()
                    || sequence < 1
                    || !Set.of("UP", "DOWN").contains(direction)) {
                throw new IllegalArgumentException("정류장 ID·방문 순번·방향이 필요하다");
            }
        }
    }

    /** 연구 브랜치 route-display-input-v1과 바이트 단위로 같은 SHA-256이다. */
    public String digest() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            string(out, "route-display-input-v1");
            string(out, routeId);
            out.writeInt(turnSequence == null ? -1 : turnSequence);
            for (Point point : points) {
                out.writeDouble(point.x());
                out.writeDouble(point.y());
            }
            out.writeInt(visits.size());
            for (Visit visit : visits) {
                string(out, visit.stationId());
                out.writeInt(visit.sequence());
                string(out, visit.direction());
                out.writeBoolean(visit.boardingAllowed());
                out.writeBoolean(visit.sourcePoint() != null);
                if (visit.sourcePoint() != null) {
                    out.writeDouble(visit.sourcePoint().x());
                    out.writeDouble(visit.sourcePoint().y());
                }
            }
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
