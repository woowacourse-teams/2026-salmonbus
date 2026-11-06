package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;

import java.util.*;

/** 모든 방문의 선상 대응 비용을 함께 최소화한다. 뒤로 간 투영을 앞 정류장 위치에 강제로 붙이지 않는다. */
final class RouteStopAlignment {
    private static final long MAX_CELLS = 2_000_000;
    static final double MAX_OFFSET = 100;
    private static final double TIE_COST = 1e-7;
    private static final double EPS = 1e-6;

    record Result(Map<Integer, Connection> connections, String failure) {
        boolean usable() {
            return failure == null;
        }
    }

    private record Row(double[] positions, double[] fractions, double[] costs) {}

    private RouteStopAlignment() {}

    static Result align(List<Point> points, List<RouteGeometry.Stop> input, boolean anchorLast) {
        var stops = input.stream().filter(stop -> stop.point() != null).toList();
        int edges = points.size() - 1;
        if (stops.isEmpty()) return new Result(Map.of(), null);
        if ((long) stops.size() * edges > MAX_CELLS) return new Result(Map.of(), "ALIGNMENT_LIMIT");
        double[] chain = new double[points.size()];
        for (int i = 1; i < points.size(); i++)
            chain[i] = chain[i - 1] + distance(points.get(i - 1), points.get(i));
        int[][] previous = new int[stops.size()][edges];
        Row row = project(points, chain, stops.getFirst().point(), anchorLast && stops.size() == 1);
        double[] costs = row.costs().clone();
        boolean[] ambiguous = new boolean[edges];
        for (int visit = 1; visit < stops.size(); visit++) {
            Row next =
                    project(
                            points,
                            chain,
                            stops.get(visit).point(),
                            anchorLast && visit == stops.size() - 1);
            double[] nextCosts = new double[edges];
            Arrays.fill(nextCosts, Double.POSITIVE_INFINITY);
            boolean[] nextAmbiguous = new boolean[edges];
            int candidate = 0, best = -1;
            double bestCost = Double.POSITIVE_INFINITY;
            boolean tied = false;
            for (int edge = 0; edge < edges; edge++) {
                while (candidate < edges
                        && row.positions()[candidate] <= next.positions()[edge] + EPS) {
                    double cost = costs[candidate];
                    if (Double.isFinite(cost)) {
                        if (best < 0 || cost < bestCost - TIE_COST) {
                            best = candidate;
                            bestCost = cost;
                            tied = ambiguous[candidate];
                        } else if (Math.abs(cost - bestCost) <= TIE_COST) {
                            tied |=
                                    ambiguous[candidate]
                                            || Math.abs(
                                                            row.positions()[candidate]
                                                                    - row.positions()[best])
                                                    > 1;
                            if (cost < bestCost) {
                                bestCost = cost;
                                best = candidate;
                            }
                        }
                    }
                    candidate++;
                }
                previous[visit][edge] = best;
                if (best >= 0 && Double.isFinite(next.costs()[edge])) {
                    nextCosts[edge] = bestCost + next.costs()[edge];
                    nextAmbiguous[edge] = tied;
                }
            }
            row = next;
            costs = nextCosts;
            ambiguous = nextAmbiguous;
        }
        int best = -1;
        double cost = Double.POSITIVE_INFINITY;
        boolean tied = false;
        for (int edge = 0; edge < edges; edge++) {
            if (!Double.isFinite(costs[edge])) continue;
            if (best < 0 || costs[edge] < cost - TIE_COST) {
                best = edge;
                cost = costs[edge];
                tied = ambiguous[edge];
            } else if (Math.abs(costs[edge] - cost) <= TIE_COST) {
                tied |=
                        ambiguous[edge]
                                || Math.abs(row.positions()[best] - row.positions()[edge]) > 1;
                if (costs[edge] < cost) {
                    best = edge;
                    cost = costs[edge];
                }
            }
        }
        if (best < 0) return new Result(Map.of(), "NO_ORDERED_STOP_ALIGNMENT");
        if (tied) return new Result(Map.of(), "AMBIGUOUS_STOP_ALIGNMENT");
        Map<Integer, Connection> connections = new HashMap<>();
        for (int visit = stops.size() - 1; visit >= 0; visit--) {
            var stop = stops.get(visit);
            boolean anchor = anchorLast && visit == stops.size() - 1;
            double fraction =
                    anchor ? 1 : fraction(points.get(best), points.get(best + 1), stop.point());
            Point point = interpolate(points.get(best), points.get(best + 1), fraction);
            connections.put(
                    stop.stopSequence(),
                    new Connection(
                            new com.gustler.backend.routegeometry.api.RouteDisplayInput.Point(
                                    point.x(), point.y()),
                            best,
                            fraction));
            if (visit > 0) best = previous[visit][best];
        }
        return new Result(Map.copyOf(connections), null);
    }

    private static Row project(List<Point> points, double[] chain, Point stop, boolean anchor) {
        int edges = points.size() - 1;
        double[] positions = new double[edges],
                fractions = new double[edges],
                costs = new double[edges];
        for (int edge = 0; edge < edges; edge++) {
            double fraction =
                    anchor && edge == edges - 1
                            ? 1
                            : fraction(points.get(edge), points.get(edge + 1), stop);
            Point point = interpolate(points.get(edge), points.get(edge + 1), fraction);
            double offset = distance(stop, point);
            positions[edge] = chain[edge] + (chain[edge + 1] - chain[edge]) * fraction;
            fractions[edge] = fraction;
            costs[edge] =
                    offset <= MAX_OFFSET && (!anchor || edge == edges - 1)
                            ? offset * offset
                            : Double.POSITIVE_INFINITY;
        }
        return new Row(positions, fractions, costs);
    }

    static double nearestDistance(List<Point> points, Point point) {
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < points.size() - 1; i++)
            nearest =
                    Math.min(
                            nearest,
                            distance(
                                    point,
                                    interpolate(
                                            points.get(i),
                                            points.get(i + 1),
                                            fraction(points.get(i), points.get(i + 1), point))));
        return nearest;
    }

    static double distance(Point a, Point b) {
        return Math.hypot(
                        (a.x() - b.x()) * Math.cos(Math.toRadians((a.y() + b.y()) / 2)),
                        a.y() - b.y())
                * 111320;
    }

    static Point interpolate(Point a, Point b, double fraction) {
        if (fraction == 0) return a;
        if (fraction == 1) return b;
        return new Point(a.x() + (b.x() - a.x()) * fraction, a.y() + (b.y() - a.y()) * fraction);
    }

    private static double fraction(Point a, Point b, Point p) {
        double scale = Math.cos(Math.toRadians(p.y())),
                dx = (b.x() - a.x()) * scale,
                dy = b.y() - a.y(),
                squared = dx * dx + dy * dy;
        if (squared == 0) return 0;
        return Math.max(
                0, Math.min(1, ((p.x() - a.x()) * scale * dx + (p.y() - a.y()) * dy) / squared));
    }
}
