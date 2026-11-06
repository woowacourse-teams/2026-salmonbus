package com.gustler.backend.routegeometry.api;

import com.gustler.backend.routegeometry.api.RouteDisplayInput.Point;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 운영자가 검수를 완료한 표시 형상. 로컬 preview의 상태명 변경을 대신하는 승인기는 아니다. */
public record ManualRouteDisplay(
        String format,
        String status,
        String algorithm,
        String routeId,
        String inputDigest,
        Map<String, Path> displayPaths,
        Map<String, Connection> connections,
        List<Visit> visits,
        Review review) {
    public static final String FORMAT = "route-display-manual-v1";

    public ManualRouteDisplay {
        if (!FORMAT.equals(format)
                || !"REVIEWED".equals(status)
                || algorithm == null
                || algorithm.isBlank()
                || routeId == null
                || !routeId.matches("[0-9]{9}")
                || inputDigest == null
                || !inputDigest.matches("[0-9a-f]{64}")
                || review == null) {
            throw new IllegalArgumentException("검수 완료된 수동 형상과 원본 지문이 필요하다");
        }
        displayPaths = Map.copyOf(displayPaths);
        connections = Map.copyOf(connections);
        visits = List.copyOf(visits);
        if (!displayPaths.keySet().equals(Set.of("UP", "DOWN"))) {
            throw new IllegalArgumentException("양방향 표시 경로가 필요하다");
        }
    }

    public record Review(String reviewer, Instant reviewedAt) {
        public Review {
            if (reviewer == null || reviewer.isBlank() || reviewedAt == null) {
                throw new IllegalArgumentException("검수자와 검수 시각이 필요하다");
            }
        }
    }

    public record Path(List<Point> points) {
        public Path {
            points = List.copyOf(points);
            if (points.size() < 2
                    || points.size() > 100_000
                    || points.stream().distinct().limit(2).count() < 2) {
                throw new IllegalArgumentException("서로 다른 표시점 두 개 이상이 필요하다");
            }
        }
    }

    public record Connection(Point point, int segmentIndex, double segmentFraction) {
        public Connection {
            if (point == null
                    || segmentIndex < 0
                    || !Double.isFinite(segmentFraction)
                    || segmentFraction < 0
                    || segmentFraction > 1) {
                throw new IllegalArgumentException("유효한 방문별 선분 연결점이 필요하다");
            }
        }
    }

    public record Visit(
            String stationId,
            int sequence,
            String direction,
            boolean boardingAllowed,
            Point sourcePoint,
            Point displayPoint) {}

    public void validateFor(RouteDisplayInput input) {
        if (!routeId.equals(input.routeId())
                || !inputDigest.equals(input.digest())
                || visits.size() != input.visits().size()) {
            throw new IllegalArgumentException("현재 노선 원본과 수동 형상이 다르다");
        }
        Map<String, Double> progress = new HashMap<>();
        Map<String, Point> sharedPositions = new HashMap<>();
        Set<String> sequences = new java.util.HashSet<>();
        for (int index = 0; index < visits.size(); index++) {
            Visit visit = visits.get(index);
            RouteDisplayInput.Visit original = input.visits().get(index);
            if (!original.stationId().equals(visit.stationId())
                    || original.sequence() != visit.sequence()
                    || !original.direction().equals(visit.direction())
                    || original.boardingAllowed() != visit.boardingAllowed()
                    || !java.util.Objects.equals(original.sourcePoint(), visit.sourcePoint())) {
                throw new IllegalArgumentException("방문 식별과 원본 좌표를 보존해야 한다");
            }
            String sequence = Integer.toString(visit.sequence());
            sequences.add(sequence);
            if (visit.sourcePoint() == null) {
                if (visit.displayPoint() != null || connections.containsKey(sequence)) {
                    throw new IllegalArgumentException("원본 좌표가 없는 정류장에 표시 좌표를 만들 수 없다");
                }
                continue;
            }
            if (visit.boardingAllowed() && visit.displayPoint() == null) {
                throw new IllegalArgumentException("승차 정류장의 표시 좌표가 필요하다");
            }
            // 경유 방문은 마커 없이 선분 연결만 보존할 수 있다.
            if (visit.displayPoint() != null) {
                Point shared = sharedPositions.putIfAbsent(visit.stationId(), visit.displayPoint());
                if (shared != null && !shared.equals(visit.displayPoint())) {
                    throw new IllegalArgumentException("같은 정류장의 표시 좌표가 다르다");
                }
            }
            Connection connection = connections.get(sequence);
            if (connection == null) {
                throw new IllegalArgumentException("좌표가 있는 모든 방문의 연결점이 필요하다");
            }
            List<Point> points = displayPaths.get(visit.direction()).points();
            if (connection.segmentIndex() >= points.size() - 1) {
                throw new IllegalArgumentException("연결 선분 인덱스가 표시 경로 밖이다");
            }
            Point a = points.get(connection.segmentIndex());
            Point b = points.get(connection.segmentIndex() + 1);
            double x = a.x() + (b.x() - a.x()) * connection.segmentFraction();
            double y = a.y() + (b.y() - a.y()) * connection.segmentFraction();
            if (Math.abs(x - connection.point().x()) > 1e-9
                    || Math.abs(y - connection.point().y()) > 1e-9) {
                throw new IllegalArgumentException("연결점과 표시 선분이 일치하지 않는다");
            }
            double at = connection.segmentIndex() + connection.segmentFraction();
            if (at < progress.getOrDefault(visit.direction(), -1d)) {
                throw new IllegalArgumentException("방문 순서가 표시 경로를 역행한다");
            }
            progress.put(visit.direction(), at);
        }
        if (!sequences.containsAll(connections.keySet())) {
            throw new IllegalArgumentException("원본에 없는 방문 연결점이다");
        }
    }
}
