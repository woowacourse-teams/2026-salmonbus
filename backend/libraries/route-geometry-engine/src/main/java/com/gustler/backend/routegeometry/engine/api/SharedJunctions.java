package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.api.RouteShapeEvidence.TypedPoint;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SharedJunctions {
    private static final double MAX_POINT_DRIFT_METRES = 35;
    private static final double MAX_LATERAL_DRIFT_METRES = 4;
    private static final double MIN_HEADING_AGREEMENT = Math.cos(Math.toRadians(15));

    private SharedJunctions() {}

    record Lane(Point from, Point to) {
        boolean hasShortcut(List<Point> points) {
            for (int i = 1; i < points.size(); i++)
                if (from.equals(points.get(i - 1)) && to.equals(points.get(i))) return true;
            return false;
        }

        List<Point> without(List<Point> points, Point center) {
            int i = points.indexOf(center);
            if (i < 1 || i + 1 >= points.size() || !points.get(i - 1).equals(from)
                    || !points.get(i + 1).equals(to) || points.lastIndexOf(center) != i) return null;
            var removed = new ArrayList<>(points);
            removed.remove(i);
            return removed;
        }
    }

    record Junction(Point center, Lane up, Lane down) {
        boolean removedFrom(RoutePaths paths) {
            return !paths.up().points().contains(center) && !paths.down().points().contains(center);
        }

        RoutePaths separate(RoutePaths paths) {
            var upPoints = up.without(paths.up().points(), center);
            var downPoints = down.without(paths.down().points(), center);
            if (upPoints == null || downPoints == null) return null;
            return new RoutePaths(new RoutePaths.Path(upPoints), new RoutePaths.Path(downPoints));
        }
    }

    private record Occurrence(Lane raw, Lane web, Point webCenter, String direction) {}

    static List<Junction> find(RouteShapeInput geometry, EvidenceRoutePaths.Classification alignment, RoutePaths paths) {
        if (paths == null || alignment.classifications().isEmpty() || !alignment.normalization().equals("FULL_POINT_ORDER_V1")
                || !geometry.shape().evidence().selectedSource().equals("GBIS_API")
                || geometry.shape().evidence().sources().stream().noneMatch(source -> source.id().equals("GBIS_WEB")
                && source.status().equals("AVAILABLE") && source.digest() != null)) return List.of();
        var shared = new HashSet<>(paths.up().points());
        shared.retainAll(new HashSet<>(paths.down().points()));
        if (shared.isEmpty()) return List.of();

        var raw = geometry.points();
        var roles = geometry.shape().evidence().webPoints();
        int boundary = 0;
        while (boundary < roles.size() && roles.get(boundary).direction().equals("UP")) boundary++;
        if (boundary == 0 || boundary == roles.size() || roles.subList(boundary, roles.size()).stream()
                .anyMatch(role -> !role.direction().equals("DOWN"))) return List.of();
        boolean merged = alignment.sharedBoundaryMerged();
        if (roles.size() != raw.size() + (merged ? 1 : 0)) return List.of();

        var occurrences = new LinkedHashMap<Point, List<Occurrence>>();
        var rejected = new HashSet<Point>();
        Map<Point,Integer> webCounts = new HashMap<>();
        for (var role : roles) webCounts.merge(point(role), 1, Integer::sum);
        for (int i = 0; i < raw.size(); i++) {
            var center = raw.get(i);
            if (!shared.contains(center)) continue;
            int roleIndex = merged && i >= boundary ? i + 1 : i;
            if (i == 0 || i + 1 == raw.size() || Math.abs(roleIndex - boundary) <= 1
                    || roles.get(roleIndex).type() != 2
                    || !flank(roles.get(roleIndex - 1), roles.get(roleIndex))
                    || !flank(roles.get(roleIndex + 1), roles.get(roleIndex))) {
                rejected.add(center);
                continue;
            }
            var witness = new Occurrence(new Lane(raw.get(i - 1), raw.get(i + 1)),
                    new Lane(point(roles.get(roleIndex - 1)), point(roles.get(roleIndex + 1))), point(roles.get(roleIndex)), roles.get(roleIndex).direction());
            if (!fits(center, witness)) rejected.add(center);
            else occurrences.computeIfAbsent(center, ignored -> new ArrayList<>()).add(witness);
        }
        List<Junction> results = new ArrayList<>();
        for (var entry : occurrences.entrySet()) {
            var center = entry.getKey();
            var pair = entry.getValue();
            if (rejected.contains(center) || pair.size() != 2) continue;
            var up = pair.getFirst();
            var down = pair.getLast();
            if (!up.direction.equals("UP") || !down.direction.equals("DOWN") || !up.webCenter.equals(down.webCenter)
                    || webCounts.get(up.webCenter) != 2 || !separateLanes(up.raw, down.raw) || !separateLanes(up.web, down.web)
                    || Math.signum(side(up.raw, down.raw.from)) != Math.signum(side(up.web, down.web.from))) continue;
            if (geometry.busStops().stream().anyMatch(s -> s.point() != null && distance(s.point(), center) <= 8
                    && !retainsNearbyStop(s, center, s.direction().equals("UP") ? up : down, alignment))) continue;
            results.add(new Junction(center, up.raw, down.raw));
        }
        return results;
    }

    private static boolean retainsNearbyStop(RouteGeometry.Stop stop, Point center, Occurrence w, EvidenceRoutePaths.Classification alignment) {
        var lane = w.raw;
        boolean anchored = List.of(lane.from, lane.to).stream().anyMatch(p -> distance(stop.point(), p) <= 4
                && alignment.classifications().getOrDefault(p, List.of()).stream().anyMatch(role -> role.type() == 1
                && role.direction().equals(stop.direction())));
        if (!anchored) return false;
        double previous = Math.min(segmentGap(stop.point(), new Lane(lane.from, center)), segmentGap(stop.point(), new Lane(center, lane.to)));
        return segmentGap(stop.point(), lane) <= previous + 0.01;
    }

    private static boolean flank(TypedPoint flank, TypedPoint center) {
        return (flank.type() == 3 || flank.type() == 1) && flank.direction().equals(center.direction());
    }

    private static boolean fits(Point center, Occurrence w) {
        var raw = w.raw;
        var web = w.web;
        if (distance(center, w.webCenter) > MAX_POINT_DRIFT_METRES
                || distance(raw.from, web.from) > MAX_POINT_DRIFT_METRES
                || distance(raw.to, web.to) > MAX_POINT_DRIFT_METRES
                || !spans(raw, center) || !spans(web, w.webCenter) || cosine(raw, web) < MIN_HEADING_AGREEMENT) return false;
        return Math.abs(side(raw, center) - side(raw, w.webCenter)) <= MAX_LATERAL_DRIFT_METRES
                && Math.max(Math.abs(side(web, raw.from)), Math.abs(side(web, raw.to))) <= MAX_LATERAL_DRIFT_METRES
                && fraction(web, raw.from) < 1 && fraction(web, raw.to) > 0;
    }

    private static boolean spans(Lane lane, Point center) {
        double length = distance(lane.from, lane.to);
        double projection = fraction(lane, center);
        return length >= 1 && length <= 500 && projection > 0 && projection < 1 && Math.abs(side(lane, center)) <= 25;
    }

    private static boolean separateLanes(Lane a, Lane b) {
        return cosine(a,b) < -0.7 && sameSide(a,b) && sameSide(b,a);
    }

    private static boolean sameSide(Lane a, Lane b) {
        double first = side(a, b.from), last = side(a, b.to);
        return first * last > 0 && Math.min(Math.abs(first), Math.abs(last)) >= 1;
    }

    private static Point point(TypedPoint p) { return new Point(p.point().x(), p.point().y()); }
    private static double distance(Point a, Point b) { return RouteStopAlignment.distance(a,b); }
    private static double east(Point a, Point b) {
        return (b.x() - a.x()) * 111320 * Math.cos(Math.toRadians(a.y()));
    }
    private static double north(Point a, Point b) { return (b.y() - a.y()) * 111320; }

    private static double cosine(Lane a, Lane b) {
        double dot = east(a.from, a.to) * east(b.from, b.to) + north(a.from, a.to) * north(b.from, b.to);
        return dot / (distance(a.from, a.to) * distance(b.from, b.to));
    }

    private static double side(Lane lane, Point p) {
        double cross = east(lane.from, lane.to) * north(lane.from, p) - north(lane.from, lane.to) * east(lane.from, p);
        return cross / distance(lane.from, lane.to);
    }

    private static double fraction(Lane lane, Point p) {
        double x = east(lane.from, lane.to);
        double y = north(lane.from, lane.to);
        return (x * east(lane.from, p) + y * north(lane.from, p)) / (x * x + y * y);
    }

    private static double segmentGap(Point p, Lane lane) {
        double t = Math.max(0, Math.min(1, fraction(lane, p)));
        var nearest = new Point(lane.from.x() + t * (lane.to.x() - lane.from.x()), lane.from.y() + t * (lane.to.y() - lane.from.y()));
        return distance(p, nearest);
    }
}
