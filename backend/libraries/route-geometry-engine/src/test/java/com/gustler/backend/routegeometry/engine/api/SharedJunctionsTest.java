package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence.TypedPoint;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Shape;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Stop;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedJunctionsTest {
    private static Point point(double x, double y) {
        return new Point(127 + x / (111320 * Math.cos(Math.toRadians(37))), 37 + y / 111320);
    }

    private static final Point CENTER = point(0, 0);

    private enum Case { ALONG_ROAD, ACROSS_ROAD, CENTER_OFF_ROAD, MIXED_ROLE, STOP_ON_NODE, REAL_CROSSING, SWAPPED_LANES }

    private RouteShapeInput fixture(Case scenario) {
        var up = new ArrayList<Point>();
        var down = new ArrayList<Point>();
        var roles = new ArrayList<TypedPoint>();
        for (String direction : List.of("UP", "DOWN")) {
            boolean outbound = direction.equals("UP");
            var leg = outbound ? up : down;
            for (int i = -45; i <= 45; i++) {
                double x = 20 * (outbound ? i : -i);
                double y = outbound || i == -45 ? -6 : 6;
                if (scenario == Case.REAL_CROSSING && !outbound && Math.abs(i) == 1) y = i < 0 ? -16 : 6;
                leg.add(i == 0 ? CENTER : point(x, y));
                boolean anchor = Math.abs(i) == 45;
                int type = anchor ? 1 : i == 0 && (outbound || scenario != Case.MIXED_ROLE) ? 2 : 3;
                double dx = Math.abs(i) <= 1 ? 12 : 0;
                double dy = i == 0 ? 0 : y;
                if (i == 0 && scenario == Case.CENTER_OFF_ROAD) dy = 12;
                if (Math.abs(i) == 1 && scenario == Case.ACROSS_ROAD) dy += 8;
                if (Math.abs(i) == 1 && scenario == Case.SWAPPED_LANES) dy = -dy;
                var web = point(x + dx, dy);
                roles.add(new TypedPoint(new RouteDisplayInput.Point(web.x(), web.y()), type, direction, false));
            }
        }
        var raw = new ArrayList<>(up);
        raw.addAll(down.subList(1, down.size()));
        var stops = new ArrayList<Stop>();
        stops.add(new Stop("a", 1, "a", "UP", true, up.getFirst()));
        if (scenario == Case.STOP_ON_NODE) stops.add(new Stop("b", 2, "b", "UP", true, CENTER));
        stops.add(new Stop("c", 3, "c", "UP", true, up.getLast()));
        stops.add(new Stop("d", 4, "d", "DOWN", true, down.getLast()));
        var shape = new Shape(new UUID(0, 1), Instant.EPOCH, raw, new RouteShapeEvidence("GBIS_API",
                List.of(new RouteShapeEvidence.Source("GBIS_WEB", "AVAILABLE", "a".repeat(64))), roles));
        var geometry = new RouteGeometry("123456789", 1, "fixture", "a", "c", 3, stops, shape);
        var paths = new RoutePaths(new RoutePaths.Path(up), new RoutePaths.Path(down));
        return new RouteShapeInput.Prepared(geometry, new RoutePathSplitter.Split(paths, up.size()-2, 1), true);
    }

    @Test void separatesBothDirectionsDespiteLongitudinalDriftAndPreservesTheUnmatchedFlanks() {
        var g = fixture(Case.ALONG_ROAD);
        var roles = EvidenceRoutePaths.classify(g);
        assertThat(roles.unmatchedPoints()).isPositive();
        var result = EvidenceRoutePaths.build(g);
        assertThat(result.up().points()).doesNotContain(CENTER).containsSubsequence(point(-20,-6),point(20,-6));
        assertThat(result.down().points()).doesNotContain(CENTER).containsSubsequence(point(20,6),point(-20,6));
        assertThat(EvidenceRoutePaths.preservesPartialSegments(g, result, roles)).isTrue();
        assertThat(EvidenceRoutePaths.preservesPartialSegments(g,
                new RoutePaths(result.up(), g.paths().down()), roles)).isFalse();
        var damaged = new ArrayList<>(result.up().points());
        damaged.remove(point(-20,-6));
        assertThat(EvidenceRoutePaths.preservesPartialSegments(g,
                new RoutePaths(new RoutePaths.Path(damaged), result.down()), roles)).isFalse();
    }

    @Test void keepsTheNodeWhenTheOtherRoadOrAStopUsesIt() {
        for (var scenario : List.of(Case.ACROSS_ROAD, Case.CENTER_OFF_ROAD, Case.MIXED_ROLE, Case.STOP_ON_NODE, Case.REAL_CROSSING, Case.SWAPPED_LANES)) {
            var g = fixture(scenario);
            assertThat(SharedJunctions.find(g, EvidenceRoutePaths.classify(g), g.paths())).as(scenario.name()).isEmpty();
            var paths = EvidenceRoutePaths.build(g);
            assertThat(paths.up().points()).contains(CENTER);
            assertThat(paths.down().points()).contains(CENTER);
        }
    }
}
