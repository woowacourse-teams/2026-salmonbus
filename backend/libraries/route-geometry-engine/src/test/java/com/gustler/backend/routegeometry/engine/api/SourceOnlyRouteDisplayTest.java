package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.QualityStatus;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.*;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceOnlyRouteDisplayTest {
    private RouteGeometry geometry(boolean far, boolean evidence) {
        var points = List.of(new Point(126.9995, 37), new Point(127, 37),
                new Point(127.0005, 37.00003), new Point(127.001, 37),
                new Point(127.001, 37.0001), new Point(127, 37.0001),
                new Point(126.9995, 37.0001));
        var typed = new ArrayList<RouteShapeEvidence.TypedPoint>();
        for (int i = 0; i < points.size(); i++) {
            var p = points.get(i);
            typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(p.x(), p.y()),
                    i == 2 ? 2 : 1, i <= 3 ? "UP" : "DOWN", true));
        }
        var proof = evidence ? new RouteShapeEvidence("GBIS_API",
                List.of(new RouteShapeEvidence.Source("GBIS_WEB", "AVAILABLE", null)), typed)
                : RouteShapeEvidence.EMPTY;
        return new RouteGeometry("204000057", 1, "3330", "A", "B", 2,
                List.of(new Stop("A", 1, "A", "UP", true,
                                far ? new Point(127, 37.0004) : points.get(1)),
                        new Stop("B", 2, "B", "UP", true, points.get(3)),
                        new Stop("A", 3, "A", "DOWN", true, points.get(5))),
                new Shape(UUID.randomUUID(), Instant.EPOCH, points, proof));
    }

    @Test
    void sourceOnlyPreservesTerminalTailsWhileCandidateTrimsThem() {
        var source = geometry(false, false);
        var fallback = AutomaticRouteDisplay.sourceOnly(source, "source-test");
        var candidate = AutomaticRouteDisplay.build(source, "source-test");
        assertThat(fallback.quality().status()).isEqualTo(QualityStatus.SOURCE_ONLY);
        assertThat(fallback.paths()).isEqualTo(source.paths());
        assertThat(candidate.paths().up().points().getFirst()).isEqualTo(source.busStops().getFirst().point());
        assertThat(candidate.paths().up().points().getFirst()).isNotEqualTo(fallback.paths().up().points().getFirst());
        assertThat(fallback.connections()).containsKeys(1, 2, 3);
        assertThat(fallback.sourcePaths()).isEqualTo(source.paths());
        assertThat(candidate.sourcePaths()).isEqualTo(source.paths());
        assertThat(candidate.sourcePaths()).isNotEqualTo(candidate.paths());
    }

    @Test
    void sourceOnlyNeverUsesSupplementaryNodeRemoval() {
        var source = geometry(false, true);
        var fallback = AutomaticRouteDisplay.sourceOnly(source, "source-test");
        assertThat(EvidenceRoutePaths.build(source).up().points()).doesNotContain(source.points().get(2));
        assertThat(fallback.paths()).isEqualTo(source.paths());
        assertThat(fallback.paths().up().points()).contains(source.points().get(2));
    }

    @Test
    void invalidSourceIsWithheldWithoutPartialConnections() {
        var fallback = AutomaticRouteDisplay.sourceOnly(geometry(true, false), "source-test");
        assertThat(fallback.quality().status()).isEqualTo(QualityStatus.WITHHELD);
        assertThat(fallback.paths()).isNull();
        assertThat(fallback.sourcePaths()).isEqualTo(geometry(true, false).paths());
        assertThat(fallback.connections()).isEmpty();
        assertThat(fallback.quality().issues()).anyMatch(issue -> issue.code().equals("STOP_CONNECTION_GAP"));
    }
}
