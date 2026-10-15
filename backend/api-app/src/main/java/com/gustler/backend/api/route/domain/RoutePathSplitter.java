package com.gustler.backend.api.route.domain;

import com.gustler.backend.api.route.domain.RouteGeometry.Point;
import com.gustler.backend.api.route.domain.RouteGeometry.Stop;
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

    private RoutePathSplitter() { }

    static RoutePaths split(RouteGeometry geometry) {
        if (geometry.shape() == null || geometry.turnSequence() == null) return null;
        var stops = geometry.busStops().stream().filter(stop -> stop.point() != null)
            .sorted(Comparator.comparingInt(Stop::stopSequence)).toList();
        int turn = -1;
        for (int i = 0; i < stops.size(); i++) {
            if (i > 0 && stops.get(i - 1).stopSequence() == stops.get(i).stopSequence()) return null;
            if (stops.get(i).stopSequence() == geometry.turnSequence()) turn = i;
        }
        // 회차 좌표와 그 양쪽의 방문 근거가 있어야 두 방향을 제공한다.
        if (turn <= 0 || turn >= stops.size() - 1
            || (long) stops.size() * (geometry.points().size() - 1) > MAX_PROJECTION_CELLS) return null;

        var line = new Line(geometry.points(), stops.get(turn).point().y());
        var forward = align(line, stops, 0, turn, 1);
        var backward = align(line, stops, stops.size() - 1, turn, -1);
        double[] costs = new double[line.edges()];
        Arrays.fill(costs, Double.POSITIVE_INFINITY);
        int best = -1;
        for (int edge = 0; edge < costs.length; edge++) {
            if (!Double.isFinite(forward.costs()[edge]) || !Double.isFinite(backward.costs()[edge])) continue;
            costs[edge] = forward.costs()[edge] + backward.costs()[edge] - forward.row().offsets()[edge];
            if (best == -1 || costs[edge] < costs[best]) best = edge;
        }
        if (best == -1) return null;
        double boundary = forward.row().positions()[best];
        if (boundary <= ORDER_EPSILON_METERS || boundary >= line.length() - ORDER_EPSILON_METERS) return null;
        for (int edge = 0; edge < costs.length; edge++) {
            if (costs[edge] <= costs[best] + EQUIVALENT_COST_SQUARED
                && Math.abs(forward.row().positions()[edge] - boundary) > DISTINCT_BOUNDARY_METERS) return null;
        }
        return cut(geometry.points(), best, forward.row().fractions()[best]);
    }

    private static Alignment align(Line line, List<Stop> stops, int first, int last, int step) {
        Row previous = line.project(stops.get(first).point());
        double[] costs = previous.offsets().clone();
        for (int i = first + step; step > 0 ? i <= last : i >= last; i += step) {
            Row current = line.project(stops.get(i).point());
            double[] next = new double[line.edges()];
            double best = Double.POSITIVE_INFINITY;
            int from = step > 0 ? 0 : line.edges() - 1;
            int candidate = from;
            for (int edge = from; edge >= 0 && edge < line.edges(); edge += step) {
                while (candidate >= 0 && candidate < line.edges()
                    && step * (current.positions()[edge] - previous.positions()[candidate]) > ORDER_EPSILON_METERS) {
                    best = Math.min(best, costs[candidate]);
                    candidate += step;
                }
                next[edge] = current.offsets()[edge] + best;
            }
            previous = current;
            costs = next;
        }
        return new Alignment(previous, costs);
    }

    private static RoutePaths cut(List<Point> points, int edge, double fraction) {
        Point a = points.get(edge);
        Point b = points.get(edge + 1);
        Point boundary = fraction == 0 ? a : fraction == 1 ? b
            : new Point(a.x() + fraction * (b.x() - a.x()), a.y() + fraction * (b.y() - a.y()));
        var up = new ArrayList<>(points.subList(0, edge + 1));
        if (!boundary.equals(a)) up.add(boundary);
        var down = new ArrayList<Point>();
        if (!boundary.equals(b)) down.add(boundary);
        down.addAll(points.subList(edge + 1, points.size()));
        if (up.stream().distinct().limit(2).count() < 2 || down.stream().distinct().limit(2).count() < 2) return null;
        return new RoutePaths(new RoutePaths.Path(up), new RoutePaths.Path(down));
    }

    private record Row(double[] positions, double[] offsets, double[] fractions) { }
    private record Alignment(Row row, double[] costs) { }

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

        private Line(List<Point> points, double latitude) {
            originX = points.getFirst().x();
            originY = points.getFirst().y();
            scaleX = METERS_PER_DEGREE * Math.cos(Math.toRadians(latitude));
            int count = points.size() - 1;
            x = new double[count]; y = new double[count];
            dx = new double[count]; dy = new double[count];
            squared = new double[count]; lengths = new double[count]; chain = new double[count + 1];
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

        private int edges() { return x.length; }
        private double length() { return chain[chain.length - 1]; }

        private Row project(Point point) {
            double px = (point.x() - originX) * scaleX;
            double py = (point.y() - originY) * METERS_PER_DEGREE;
            double[] positions = new double[edges()];
            double[] offsets = new double[edges()];
            double[] fractions = new double[edges()];
            for (int edge = 0; edge < edges(); edge++) {
                double fraction = squared[edge] == 0 ? 0 : Math.clamp(
                    ((px - x[edge]) * dx[edge] + (py - y[edge]) * dy[edge]) / squared[edge], 0, 1);
                double ex = px - x[edge] - fraction * dx[edge];
                double ey = py - y[edge] - fraction * dy[edge];
                double distance = ex * ex + ey * ey;
                offsets[edge] = distance <= MAX_STOP_OFFSET_SQUARED ? distance : Double.POSITIVE_INFINITY;
                positions[edge] = chain[edge] + fraction * lengths[edge];
                fractions[edge] = fraction;
            }
            return new Row(positions, offsets, fractions);
        }
    }
}
