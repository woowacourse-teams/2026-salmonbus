package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence.TypedPoint;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import java.util.*;

final class TurnSubsegmentSupport {
    private TurnSubsegmentSupport() {}

    record Witness(int referenceEndpointIndex, int rawSegmentIndex, double rawFraction) {}

    static Optional<Witness> certify(RouteGeometry geometry, String direction, List<Point> reference,
            Map<Integer, Connection> connections, Map<Point, List<TypedPoint>> classified,
            Optional<SourceBoundDisplayCurves.TerminalSubsegmentSupport> terminal) {
        if (reference.size() < 2 || !(direction.equals("UP") || direction.equals("DOWN"))) return Optional.empty();
        var split = RoutePathSplitter.splitWithBoundary(geometry);
        if (split == null || !(split.sourceFraction() > 0 && split.sourceFraction() < 1)) return Optional.empty();
        boolean start = direction.equals("DOWN");
        int endpoint = start ? 0 : reference.size() - 1;
        Point boundary = split.paths().down().points().getFirst();
        if (!boundary.equals(split.paths().up().points().getLast()) || !boundary.equals(reference.get(endpoint))) return Optional.empty();
        int edge = split.sourceSegmentIndex();
        Point a = geometry.points().get(edge), b = geometry.points().get(edge + 1);
        if (!boundary.equals(RouteStopAlignment.interpolate(a, b, split.sourceFraction()))) return Optional.empty();
        for (Point p : List.of(a, b)) {
            if (classified.getOrDefault(p, List.of()).isEmpty()
                    || geometry.points().stream().filter(p::equals).count() != 1) return Optional.empty();
        }
        Point adjacent = start ? b : a;
        if (!reference.get(start ? 1 : reference.size() - 2).equals(adjacent)
                || classified.get(adjacent).stream().noneMatch(p -> direction.equals(p.direction()))) return Optional.empty();
        var turnVisits = geometry.busStops().stream().filter(s -> Objects.equals(s.stopSequence(), geometry.turnSequence())).toList();
        if (turnVisits.size() != 1 || !turnVisits.getFirst().direction().equals("UP") || turnVisits.getFirst().point() == null) return Optional.empty();
        if (!start) {
            var visits = geometry.busStops().stream().filter(s -> s.direction().equals("UP")).toList();
            var aligned = RouteStopAlignment.align(reference, visits, true);
            var expected = aligned.connections().get(geometry.turnSequence());
            var actual = connections.get(geometry.turnSequence());
            if (!aligned.usable() || actual == null || !actual.equals(expected)
                    || actual.segmentIndex() != reference.size() - 2 || actual.segmentFraction() != 1
                    || !boundary.equals(new Point(actual.point().x(), actual.point().y()))) return Optional.empty();
        }
        var original = start ? split.paths().down().points() : split.paths().up().points();
        int otherEndpoint = start ? reference.size() - 1 : 0;
        Point otherOriginal = start ? original.getLast() : original.getFirst();
        boolean clippedOther = !reference.get(otherEndpoint).equals(otherOriginal);
        if (clippedOther && (terminal.isEmpty() || terminal.get().referenceEndpointIndex() != otherEndpoint)) return Optional.empty();
        int source = start ? 1 : 0;
        int first = start || clippedOther ? 1 : 0;
        int endExclusive = start && !clippedOther ? reference.size() : reference.size() - 1;
        for (int i = first; i < endExclusive; i++) {
            while (source < original.size() && !original.get(source).equals(reference.get(i))) source++;
            if (source == original.size()) return Optional.empty();
            source++;
        }
        return Optional.of(new Witness(endpoint, edge, split.sourceFraction()));
    }
}
