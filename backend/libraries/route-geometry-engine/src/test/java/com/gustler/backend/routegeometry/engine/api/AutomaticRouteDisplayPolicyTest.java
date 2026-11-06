package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Stop;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutomaticRouteDisplayPolicyTest {
    private static Point p(double x, double y) {
        return new Point(127 + x / (111320 * Math.cos(Math.toRadians(37))), 37 + y / 111320);
    }

    private static Stop stop(int sequence, String direction, Point point) {
        return new Stop("station:" + sequence, sequence, "stop", direction, true, point);
    }

    private static RouteGeometry source(List<Point> points, List<Stop> stops, int turn, int nodeIndex) {
        var typed = new ArrayList<RouteShapeEvidence.TypedPoint>();
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            String direction = i <= turn ? "UP" : "DOWN";
            int role = i == nodeIndex ? 2 : stops.stream().anyMatch(s -> point.equals(s.point())) ? 1 : 3;
            typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(point.x(), point.y()), role, direction, true));
        }
        var evidence = new RouteShapeEvidence("GBIS_API", List.of(new RouteShapeEvidence.Source("GBIS_WEB", "AVAILABLE", "a".repeat(64))), typed);
        return new RouteGeometry("synthetic", 1, "route", "A", "B", stops.stream().filter(s -> s.direction().equals("UP")).mapToInt(Stop::stopSequence).max().orElseThrow(),
                stops, new RouteGeometry.Shape(new UUID(0, 1), Instant.EPOCH, points, evidence));
    }

    @Test
    void keepsUsableNodeRemovalWhenTheStopGapIncreasesByMoreThanFiveMetres() {
        var points = List.of(p(0, 0), p(50, 20), p(100, 0), p(100, -100), p(0, -100));
        var source = source(points, List.of(stop(1, "UP", points.getFirst()), stop(2, "UP", p(50, 29)),
                stop(3, "UP", points.get(2)), stop(4, "DOWN", points.getLast())), 2, 1);

        var result = AutomaticRouteDisplay.build(source, "policy");

        assertThat(result.quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.REVIEW_REQUIRED);
        assertThat(result.paths().up().points()).doesNotContain(points.get(1));
        assertThat(result.quality().issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("STOP_CONNECTION_WORSENED");
            assertThat(issue.sequence()).isEqualTo(2);
            assertThat(issue.meters()).isGreaterThan(5);
        });
        assertThat(result.connections()).containsKeys(1, 2, 3, 4);
        assertThat(RouteStopIntervals.evaluate(source.busStops(), result.paths(), result.connections()).usable()).isTrue();
    }

    @Test
    void selectsAGeneratedCurveWithARelativeCurvatureWarning() {
        var source = curvedSource();
        var baseline = AutomaticRouteDisplay.sourceOnly(source, "policy");
        var ordinary = SourceBoundDisplayCurves.build(source, baseline.paths(), baseline.connections());
        assertThat(ordinary.curveCount()).isPositive();
        var measured = withRegression(ordinary, true);

        var result = AutomaticRouteDisplay.selectCurveFloor(source, "policy", baseline, ordinary, measured, false);

        assertThat(result.rejections()).isEmpty();
        assertThat(result.geometry().paths()).isEqualTo(measured.paths()).isNotEqualTo(baseline.paths());
        assertThat(result.geometry().quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.REVIEW_REQUIRED);
        assertThat(result.geometry().quality().issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("CURVE_FIXED_FIVE_METRE_REGRESSION");
            assertThat(issue.direction()).isEqualTo("UP");
        });
    }

    @Test
    void stillRejectsAnUncertifiedCurveEvenWhenItsOtherIssueIsOnlyAQualityWarning() {
        var source = curvedSource();
        var baseline = AutomaticRouteDisplay.sourceOnly(source, "policy");
        var ordinary = SourceBoundDisplayCurves.build(source, baseline.paths(), baseline.connections());

        var result = AutomaticRouteDisplay.selectCurveFloor(source, "policy", baseline, ordinary, withRegression(ordinary, false), false);

        assertThat(result.geometry()).isNull();
        assertThat(result.rejections()).contains("CURVE_SOURCE_BOUND_NOT_CERTIFIED").doesNotContain("CURVE_FIXED_FIVE_METRE_REGRESSION");
    }

    private static SourceBoundDisplayCurves.Plan withRegression(SourceBoundDisplayCurves.Plan plan, boolean certified) {
        return new SourceBoundDisplayCurves.Plan(plan.referenceDomain(), plan.paths(), plan.correspondences(),
                Map.of("UP", new SourceBoundDisplayCurves.Curvature(10, 0, 1, 55, 61, false)), plan.curveCount(),
                plan.maximumCertifiedDeviationMetres(), certified, plan.serializedChordCorrespondenceCertified(), plan.notes());
    }

    private static RouteGeometry curvedSource() {
        var points = List.of(p(-80, 0), p(0, 0), p(0, 80), p(40, 80), p(40, -40), p(-80, 0));
        return source(points, List.of(stop(1, "UP", points.getFirst()), stop(2, "UP", points.get(3)), stop(3, "DOWN", points.getLast())), 3, -1);
    }
}
