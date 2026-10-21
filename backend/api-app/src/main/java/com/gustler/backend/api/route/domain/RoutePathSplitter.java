package com.gustler.backend.api.route.domain;

import com.gustler.backend.api.route.domain.RouteGeometry.Point;
import com.gustler.backend.api.route.domain.RouteGeometry.Stop;
import com.gustler.backend.api.route.domain.RoutePaths.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 정류장 방문 순서를 지키는 선상 대응으로 회차 경계를 찾는다. 차량 위치 추정과 무관하다. */
final class RoutePathSplitter {
    // 규칙이 바뀌면 기존 geometry ETag도 무효화한다.
    static final String REVISION = "ordered-turn-v1";
    private static final double METERS_PER_DEGREE = 111_195.08;
    // WGS84 가정 아래 정류장과 노선이 크게 어긋난 자료를 제외하는 기준이며 위치 정확도가 아니다.
    private static final double MAX_STOP_OFFSET_SQUARED = 100 * 100;
    private static final double ORDER_EPSILON_METERS = 0.000001;
    private static final double EQUIVALENT_COST_SQUARED = 1;
    private static final double DISTINCT_BOUNDARY_METERS = 1;
    // 원본은 최대 10만 점까지 허용하므로 GET에서의 정적 가공량을 별도로 제한한다.
    private static final long MAX_PROJECTION_CELLS = 2_000_000;

    private RoutePathSplitter() {
    }

    static RoutePaths split(RouteGeometry geometry) {
        if (geometry.shape() == null || geometry.turnSequence() == null) {
            return null;
        }

        List<Stop> stops = geometry.busStops()
            .stream()
            .filter(stop -> stop.point() != null)
            .sorted(Comparator.comparingInt(Stop::stopSequence))
            .toList();
        List<Point> points = geometry.points();

        int turn = -1;
        for (int i = 0; i < stops.size(); i++) {
            if (i > 0 && stops.get(i - 1).stopSequence() == stops.get(i).stopSequence()) {
                return null;
            }
            if (stops.get(i).stopSequence() == geometry.turnSequence()) {
                turn = i;
            }
        }

        // 회차 좌표와 그 양쪽의 방문 근거가 있어야 두 방향을 제공한다.
        if (hasInvalidTurnBoundary(turn, stops.size()) || exceedsProjectionLimit(stops, points)) {
            return null;
        }

        Point turnPoint = stops.get(turn).point();
        Line line = new Line(points, turnPoint.y());
        Alignment forward = align(line, stops, 0, turn, 1);
        Alignment backward = align(line, stops, stops.size() - 1, turn, -1);
        Row forwardRow = forward.row();
        double[] forwardCosts = forward.costs();
        double[] backwardCosts = backward.costs();
        double[] costs = new double[line.edges()];
        Arrays.fill(costs, Double.POSITIVE_INFINITY);

        int best = -1;
        for (int edge = 0; edge < costs.length; edge++) {
            if (!Double.isFinite(forwardCosts[edge]) || !Double.isFinite(backwardCosts[edge])) {
                continue;
            }
            costs[edge] = forwardCosts[edge] + backwardCosts[edge] - forwardRow.offsets()[edge];
            if (best == -1 || costs[edge] < costs[best]) {
                best = edge;
            }
        }
        if (best == -1) {
            return null;
        }

        double[] forwardPositions = forwardRow.positions();
        final double boundary = forwardPositions[best];
        if (boundary <= ORDER_EPSILON_METERS || boundary >= line.length() - ORDER_EPSILON_METERS) {
            return null;
        }
        for (int edge = 0; edge < costs.length; edge++) {
            if (costs[edge] <= costs[best] + EQUIVALENT_COST_SQUARED
                && Math.abs(forwardPositions[edge] - boundary) > DISTINCT_BOUNDARY_METERS) {
                return null;
            }
        }
        return cut(points, best, forwardRow.fractions()[best]);
    }

    private static boolean hasInvalidTurnBoundary(int turn, int stopCount) {
        return turn <= 0 || turn >= stopCount - 1;
    }

    private static boolean exceedsProjectionLimit(List<Stop> stops, List<Point> points) {
        return (long) stops.size() * (points.size() - 1) > MAX_PROJECTION_CELLS;
    }

    /**
     * step 방향으로 정류장과 선분을 순회하며 허용 오차보다 앞서는 위치의 최소 비용을 누적한다. 배열 인덱스와 수치 계산 순서를 함께 유지한다.
     */
    private static Alignment align(
        Line line,
        List<Stop> stops,
        final int first,
        final int last,
        final int step
    ) {
        Row previous = line.project(stops.get(first).point());
        double[] costs = previous.offsets().clone();
        final int edgeCount = line.edges();
        for (int i = first + step; hasRemainingStop(i, last, step); i += step) {
            Row current = line.project(stops.get(i).point());
            double[] currentPositions = current.positions();
            double[] previousPositions = previous.positions();
            double[] currentOffsets = current.offsets();
            double[] next = new double[edgeCount];
            double best = Double.POSITIVE_INFINITY;
            final int from = firstEdge(line, step);
            int candidate = from;
            for (int edge = from; edge >= 0 && edge < edgeCount; edge += step) {
                while (isCandidateInRange(candidate, edgeCount)) {
                    if (!isCandidateBeforeCurrentPosition(
                        step,
                        currentPositions[edge],
                        previousPositions[candidate]
                    )) {
                        break;
                    }
                    best = Math.min(best, costs[candidate]);
                    candidate += step;
                }
                next[edge] = currentOffsets[edge] + best;
            }

            previous = current;
            costs = next;
        }
        return new Alignment(previous, costs);
    }

    private static boolean isCandidateInRange(int candidate, int edgeCount) {
        return candidate >= 0 && candidate < edgeCount;
    }

    private static boolean isCandidateBeforeCurrentPosition(
        int step,
        double currentPosition,
        double previousPosition
    ) {
        return step * (currentPosition - previousPosition) > ORDER_EPSILON_METERS;
    }

    private static boolean hasRemainingStop(final int index, final int last, final int step) {
        if (step > 0) {
            return index <= last;
        }
        return index >= last;
    }

    private static int firstEdge(Line line, final int step) {
        if (step > 0) {
            return 0;
        }
        return line.edges() - 1;
    }

    private static RoutePaths cut(List<Point> points, final int edge, final double fraction) {
        Point a = points.get(edge);
        Point b = points.get(edge + 1);
        Point boundary = interpolate(a, b, fraction);
        ArrayList<Point> up = new ArrayList<>(points.subList(0, edge + 1));
        if (!boundary.equals(a)) {
            up.add(boundary);
        }
        ArrayList<Point> down = new ArrayList<Point>();
        if (!boundary.equals(b)) {
            down.add(boundary);
        }
        down.addAll(points.subList(edge + 1, points.size()));
        if (up.stream()
            .distinct()
            .limit(2)
            .count() < 2 || down.stream()
            .distinct()
            .limit(2)
            .count() < 2) {
            return null;
        }
        return new RoutePaths(new Path(up), new Path(down));
    }

    /** 끝점이면 원본 점을 그대로 쓰고, 중간점의 덧셈·곱셈 순서는 좌표별 기존 보간식대로 유지한다. */
    private static Point interpolate(Point a, Point b, final double fraction) {
        if (fraction == 0) {
            return a;
        }
        if (fraction == 1) {
            return b;
        }
        return new Point(a.x() + fraction * (b.x() - a.x()), a.y() + fraction * (b.y() - a.y()));
    }

    /** 형상 정렬 한 행의 내부 계산 배열이다. 같은 호출이 소유하며 응답 DTO에는 배열을 노출하지 않는다. */
    private record Row(double[] positions, double[] offsets, double[] fractions) {
    }
    /** 마지막 투영 행과 선분별 누적 비용이다. 같은 정렬 호출 안에서만 사용하는 계산 버퍼다. */
    private record Alignment(Row row, double[] costs) {
    }

    private static final class Line {
        private final double originX;
        private final double originY;
        private final double scaleX;
        private final double[] x;
        private final double[] y;
        private final double[] dx;
        private final double[] dy;
        private final double[] squared;
        private final double[] lengths;
        private final double[] chain;

        private Line(List<Point> points, final double latitude) {
            originX = points.getFirst().x();
            originY = points.getFirst().y();
            scaleX = METERS_PER_DEGREE * Math.cos(Math.toRadians(latitude));
            final int count = points.size() - 1;
            x = new double[count];
            y = new double[count];
            dx = new double[count];
            dy = new double[count];
            squared = new double[count];
            lengths = new double[count];
            chain = new double[count + 1];
            for (int edge = 0; edge < count; edge++) {
                Point a = points.get(edge);
                Point b = points.get(edge + 1);
                x[edge] = (a.x() - originX) * scaleX;
                y[edge] = (a.y() - originY) * METERS_PER_DEGREE;
                dx[edge] = (b.x() - a.x()) * scaleX;
                dy[edge] = (b.y() - a.y()) * METERS_PER_DEGREE;
                squared[edge] = dx[edge] * dx[edge] + dy[edge] * dy[edge];
                lengths[edge] = Math.sqrt(squared[edge]);
                chain[edge + 1] = chain[edge] + lengths[edge];
            }
        }

        private int edges() {
            return x.length;
        }

        private double length() {
            return chain[chain.length - 1];
        }

        private Row project(Point point) {
            final double px = (point.x() - originX) * scaleX;
            final double py = (point.y() - originY) * METERS_PER_DEGREE;
            double[] positions = new double[edges()];
            double[] offsets = new double[edges()];
            double[] fractions = new double[edges()];
            for (int edge = 0; edge < edges(); edge++) {
                final double fraction = projectionFraction(edge, px, py);
                final double ex = px - x[edge] - fraction * dx[edge];
                final double ey = py - y[edge] - fraction * dy[edge];
                final double distance = ex * ex + ey * ey;
                offsets[edge] = boundedOffset(distance);
                positions[edge] = chain[edge] + fraction * lengths[edge];
                fractions[edge] = fraction;
            }
            return new Row(positions, offsets, fractions);
        }

        private double projectionFraction(final int edge, final double px, final double py) {
            if (squared[edge] == 0) {
                return 0;
            }
            return Math.clamp(
                ((px - x[edge]) * dx[edge] + (py - y[edge]) * dy[edge]) / squared[edge], 0, 1);
        }

        private static double boundedOffset(final double distance) {
            if (distance <= MAX_STOP_OFFSET_SQUARED) {
                return distance;
            }
            return Double.POSITIVE_INFINITY;
        }
    }
}
