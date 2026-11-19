package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.Quality;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.QualityIssue;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.QualityStatus;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PublishedTurnBoundary {
    private PublishedTurnBoundary() {}

    static RouteDisplayGeometry repartition(RouteShapeInput.Prepared verified, String digest, RouteDisplayGeometry prior) {
        var source = verified.source();
        if (prior == null || prior.paths() == null || prior.quality() == null
                || prior.source() != RouteDisplayGeometry.Source.AUTOMATIC_FALLBACK
                || !prior.stationPositions().isEmpty() || !prior.evidence().equals(source.shape().evidence().sources())) return null;
        var grade = prior.quality().status();
        if (grade != QualityStatus.PASSED && grade != QualityStatus.REVIEW_REQUIRED) return null;
        String input = "/" + digest + "/" + source.shape().evidence().revision() + "/";
        if (!prior.revision().equals(AutomaticRouteDisplay.PREVIOUS_REVISION + input + grade)) return null;

        var projected = source.directionBoundary();
        if (projected == null || !projected.paths().equals(prior.sourcePaths())) return null;
        List<Point> up = prior.paths().up().points(), down = prior.paths().down().points();
        Point seam = up.getLast();
        if (!seam.equals(down.getFirst()) || !seam.equals(projected.paths().up().points().getLast())) return null;
        int oldTurn = up.size() - 1;
        var points = new ArrayList<>(up);
        points.addAll(down.subList(1, down.size()));
        int rawVertex = verified.directionBoundary().sourceSegmentIndex() + 1;
        int newTurn = locateTurn(points, oldTurn, source, projected, rawVertex);
        if (newTurn <= 0 || newTurn >= points.size() - 1) return null;

        if (points.subList(0, newTurn + 1).stream().allMatch(points.getFirst()::equals)
                || points.subList(newTurn, points.size()).stream().allMatch(points.getLast()::equals)) return null;
        var paths = new RoutePaths(new RoutePaths.Path(points.subList(0, newTurn + 1)),
                new RoutePaths.Path(points.subList(newTurn, points.size())));
        var connections = new HashMap<Integer, Connection>();
        var issues = new ArrayList<>(prior.quality().issues());
        issues.removeIf(i -> i.code().equals("STOP_CONNECTION_GAP") && source.turnSequence().equals(i.sequence()));
        for (var stop : source.busStops()) {
            if (stop.point() == null) continue;
            Connection previous = prior.connections().get(stop.stopSequence());
            if (previous == null) return null;
            Connection next;
            if (stop.stopSequence() == source.turnSequence()) {
                if (!stop.direction().equals("UP") || previous.segmentIndex() != oldTurn - 1
                        || !previous.point().equals(asDisplayPoint(seam))) return null;
                next = new Connection(asDisplayPoint(points.get(newTurn)), newTurn - 1, 1);
                double gap = RouteStopAlignment.distance(stop.point(), points.get(newTurn));
                if (gap > RouteStopAlignment.MAX_OFFSET) return null;
                if (stop.boardingAllowed() && gap > 15) {
                    issues.add(new QualityIssue("STOP_CONNECTION_GAP", "UP", stop.stopSequence(), Math.rint(gap * 1000) / 1000));
                }
            } else {
                int edge = previous.segmentIndex() + (stop.direction().equals("DOWN") ? oldTurn - newTurn : 0);
                var leg = stop.direction().equals("UP") ? paths.up() : paths.down();
                if (edge < 0 || edge >= leg.points().size() - 1) return null;
                next = new Connection(previous.point(), edge, previous.segmentFraction());
            }
            connections.put(stop.stopSequence(), next);
        }
        if (connections.size() != prior.connections().size()
                || !RouteStopIntervals.evaluate(source.busStops(), paths, connections).usable()) return null;
        QualityStatus status = issues.isEmpty() ? QualityStatus.PASSED : QualityStatus.REVIEW_REQUIRED;
        return new RouteDisplayGeometry(paths, connections, Map.of(), AutomaticRouteDisplay.REVISION + input + status,
                prior.source(), prior.evidence(), new Quality(status, issues), verified.paths());
    }

    private static int locateTurn(List<Point> display, int start, RouteGeometry source, RoutePathSplitter.Split split, int target) {
        var raw = source.points();
        var roles = EvidenceRoutePaths.classify(source).classifications();
        int edge = split.sourceSegmentIndex();
        double fraction = split.sourceFraction();
        int step = target > edge + fraction ? 1 : -1;
        int first = step > 0 ? edge + (fraction == 1 ? 2 : 1) : edge - (fraction == 0 ? 1 : 0);
        int index = start;
        for (int i = first; step * (target - i) >= 0; i += step) {
            Point rawPoint = raw.get(i);
            var labels = roles.getOrDefault(rawPoint, List.of());
            if (i != target && (labels.isEmpty() || labels.stream().anyMatch(role -> role.type() == 1))) return -1;
            int next = index + step;
            if (next >= 0 && next < display.size() && display.get(next).equals(rawPoint)) {
                index = next;
            } else if (i == target || !labels.stream().allMatch(role -> role.type() == 2)) {
                return -1;
            }
        }
        return display.get(index).equals(raw.get(target)) ? index : -1;
    }

    private static RouteDisplayInput.Point asDisplayPoint(Point p) {
        return new RouteDisplayInput.Point(p.x(), p.y());
    }
}
