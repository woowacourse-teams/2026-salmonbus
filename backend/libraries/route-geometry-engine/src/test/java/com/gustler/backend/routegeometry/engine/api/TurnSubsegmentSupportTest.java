package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TurnSubsegmentSupportTest {
    private static Point p(double x, double y) {
        return new Point(127 + x / (111320 * Math.cos(Math.toRadians(37))), 37 + y / 111320);
    }

    private static RouteGeometry fixture(boolean evidence, boolean repeated, double turnY, boolean mirror) {
        var raw = new ArrayList<>(List.of(p(-60, 0), p(0, 0), p(0, -60), p(15, -60), p(15, 40), p(80, 40)));
        if (repeated) raw.add(p(0, 0));
        raw.add(p(80, 100));
        var stops = new ArrayList<>(List.of(new Stop("first", 1, "first", "UP", true, p(-50, 0)),
                new Stop("turn", 2, "turn", "UP", true, p(0, turnY)),
                new Stop("after", 3, "after", "DOWN", true, p(15, 30)),
                new Stop("last", 4, "last", "DOWN", true, p(80, 90))));
        if (mirror) {
            raw.replaceAll(TurnSubsegmentSupportTest::mirror);
            stops.replaceAll(s -> new Stop(s.stationId(), s.stopSequence(), s.name(), s.direction(), s.boardingAllowed(), mirror(s.point())));
        }
        var typed = new ArrayList<RouteShapeEvidence.TypedPoint>();
        for (String direction : List.of("UP", "DOWN")) {
            int from = direction.equals("UP") ? 0 : 1;
            int to = direction.equals("UP") ? 1 : raw.size() - 1;
            for (int i = from; i <= to; i++) {
                var point = raw.get(i);
                typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(point.x(), point.y()),
                        i <= 1 || i == raw.size() - 1 ? 1 : 3, direction, false));
            }
        }
        var proof = evidence ? new RouteShapeEvidence("GBIS_API",
                List.of(new RouteShapeEvidence.Source("GBIS_WEB", "AVAILABLE", "a".repeat(64))), typed) : RouteShapeEvidence.EMPTY;
        return new RouteGeometry("123456789", 1, "any route", "A", "B", 2, stops,
                new Shape(new UUID(0, 1), Instant.EPOCH, raw, proof));
    }

    private static Point mirror(Point p) { return new Point(254 - p.x(), 74 - p.y()); }

    private static Map<Integer, Connection> connections(RouteGeometry g, RoutePaths paths) {
        var result = new HashMap<Integer, Connection>();
        for (String direction : List.of("UP", "DOWN")) {
            var points = direction.equals("UP") ? paths.up().points() : paths.down().points();
            var visits = g.busStops().stream().filter(s -> s.direction().equals(direction)).toList();
            var aligned = RouteStopAlignment.align(points, visits, direction.equals("UP"));
            assertThat(aligned.usable()).isTrue();
            result.putAll(aligned.connections());
        }
        return result;
    }

    private static Optional<TurnSubsegmentSupport.Witness> support(RouteGeometry g, String direction,
            List<Point> reference, Map<Integer, Connection> connections) {
        var classified = EvidenceRoutePaths.classify(g).classifications();
        var terminal = SourceBoundDisplayCurves.terminalSubsegmentSupport(g, direction, reference, connections, classified);
        return TurnSubsegmentSupport.certify(g, direction, reference, connections, classified, terminal);
    }

    @Test void orderedSplitCarriesTheRawSegmentAndFractionWithoutChangingEitherPath() {
        var g = fixture(true, false, -10, false);
        var split = RoutePathSplitter.splitWithBoundary(g);
        assertThat(split.paths()).isEqualTo(g.paths());
        assertThat(split.sourceSegmentIndex()).isEqualTo(1);
        assertThat(split.sourceFraction()).isBetween(0d, 1d);
        var raw = g.points();
        assertThat(RouteStopAlignment.interpolate(raw.get(1), raw.get(2), split.sourceFraction()))
                .isEqualTo(split.paths().up().points().getLast()).isEqualTo(split.paths().down().points().getFirst());
    }

    @Test void bothDirectedTurnRemaindersAreCertifiedWithoutInventingTypes() {
        var g = fixture(true, false, -10, false); var paths = g.paths(); var c = connections(g, paths);
        var before = EvidenceRoutePaths.classify(g).classifications();
        assertThat(support(g, "UP", paths.up().points(), c)).isPresent();
        assertThat(support(g, "DOWN", paths.down().points(), c)).isPresent();
        assertThat(before).doesNotContainKey(paths.down().points().getFirst());
        var ordinary = SourceBoundDisplayCurves.build(g, paths, c);
        var plan = AnchorPreservingDisplayCurves.buildWithTurnSupport(g, ordinary, c).plan();
        assertThat(plan.curveCount()).isGreaterThan(ordinary.curveCount());
        assertThat(plan.notes()).contains("TURN_SUBSEGMENT_CURVE:EXACT_ORDERED_SOURCE_OCCURRENCE");
        assertThat(SourceBoundDisplayCurves.endpointsPreserved(paths, plan.paths())).isTrue();
        assertThat(plan.maximumCertifiedDeviationMetres()).isLessThanOrEqualTo(2);
        assertThat(RouteDisplayTopology.noNewContacts(paths, plan.paths())).isTrue();
        assertThat(EvidenceRoutePaths.classify(g).classifications()).isEqualTo(before);
    }

    @Test void mirroredRouteAndDifferentTurnFractionUseTheSameRule() {
        var g = fixture(true, false, -20, true); var paths = g.paths(); var c = connections(g, paths);
        assertThat(support(g, "UP", paths.up().points(), c)).isPresent();
        assertThat(support(g, "DOWN", paths.down().points(), c)).isPresent();
        var built = AutomaticRouteDisplay.buildDetailed(g, RouteDisplayGeneration.input(g).digest());
        assertThat(built.appliedCurves()).isPositive();
        assertThat(built.geometry().connections()).containsKeys(1, 2, 3, 4);
        assertThat(built.geometry().paths().down().points().getFirst()).isEqualTo(paths.down().points().getFirst());
    }

    @Test void terminalClippingAtTheOtherEndMustKeepItsIndependentSourceProof() {
        var g = fixture(true, false, -10, false);
        var built = AutomaticRouteDisplay.buildDetailed(g, RouteDisplayGeneration.input(g).digest());
        var map = built.curveCandidate().correspondences().get("DOWN");
        assertThat(map.referencePoints().getLast()).isNotEqualTo(g.paths().down().points().getLast());
        var paths = new RoutePaths(new RoutePaths.Path(built.curveCandidate().correspondences().get("UP").referencePoints()),
                new RoutePaths.Path(map.referencePoints()));
        assertThat(support(g, "DOWN", map.referencePoints(), connections(g, paths))).isPresent();
        var forged = new ArrayList<>(map.referencePoints()); forged.set(forged.size() - 1, p(80, 89));
        assertThat(support(g, "DOWN", forged, connections(g, paths))).isEmpty();
    }

    @Test void repeatedRawEndpointCannotBorrowASecondOccurrence() {
        var g = fixture(true, true, -10, false); var paths = g.paths(); var c = connections(g, paths);
        assertThat(support(g, "UP", paths.up().points(), c)).isEmpty();
        assertThat(support(g, "DOWN", paths.down().points(), c)).isEmpty();
    }

    @Test void missingWebRolesNeverAuthorizesTheDerivedBoundary() {
        var g = fixture(false, false, -10, false); var paths = g.paths(); var c = connections(g, paths);
        assertThat(support(g, "DOWN", paths.down().points(), c)).isEmpty();
        assertThat(SourceBoundDisplayCurves.build(g, paths, c).curveCount()).isZero();
    }

    @Test void aTurnAlreadyAtARawVertexNeedsNoDerivedBoundarySupport() {
        var g = fixture(true, false, 0, false); var paths = g.paths(); var c = connections(g, paths);
        assertThat(support(g, "UP", paths.up().points(), c)).isEmpty();
        assertThat(support(g, "DOWN", paths.down().points(), c)).isEmpty();
    }

    @Test void forgedBoundaryAndReversedOrReorderedPathCannotUseTheWitness() {
        var g = fixture(true, false, -10, false); var paths = g.paths(); var c = connections(g, paths);
        var forged = new ArrayList<>(paths.down().points()); forged.set(0, p(0, -11));
        assertThat(support(g, "DOWN", forged, c)).isEmpty();
        var reversed = new ArrayList<>(paths.down().points()); Collections.reverse(reversed);
        assertThat(support(g, "DOWN", reversed, c)).isEmpty();
        var reordered = new ArrayList<>(paths.down().points()); Collections.swap(reordered, 2, 3);
        assertThat(support(g, "DOWN", reordered, c)).isEmpty();
        assertThat(support(g, "UP", paths.down().points(), c)).isEmpty();
    }

    @Test void anUnrelatedInsertedInteriorCoordinateCannotBorrowSourceOrder() {
        var g = fixture(true, false, -10, false); var paths = g.paths(); var c = connections(g, paths);
        var forged = new ArrayList<>(paths.down().points()); forged.add(3, p(12, -55));
        assertThat(support(g, "DOWN", forged, c)).isEmpty();
    }

    @Test void turnVisitConnectionMustKeepItsActualBoundaryOccurrence() {
        var g = fixture(true, false, -10, false); var paths = g.paths(); var c = connections(g, paths);
        var forged = new HashMap<>(c); var actual = forged.get(2);
        forged.put(2, new Connection(actual.point(), actual.segmentIndex(), .5));
        assertThat(support(g, "UP", paths.up().points(), forged)).isEmpty();
        forged.remove(2);
        assertThat(support(g, "UP", paths.up().points(), forged)).isEmpty();
    }

    @Test void anotherTurnSelectionCannotReuseTheOldBoundary() {
        var g = fixture(true, false, -10, false); var old = g.paths();
        var newer = fixture(true, false, -20, false);
        assertThat(support(newer, "DOWN", old.down().points(), connections(g, old))).isEmpty();
    }

    @Test void changedAlgorithmRevisionDoesNotReuseThePreviousArtifactKey() {
        var g = fixture(true, false, -10, false); var digest = RouteDisplayGeneration.input(g).digest();
        assertThat(RouteDisplayGeneration.key(digest, "evidence", AutomaticRouteDisplay.REVISION))
                .isNotEqualTo(RouteDisplayGeneration.key(digest, "evidence", "automatic-quality-v8"));
    }

    @Test void turnSupportAlsoWorksWhenThePreviousLayersCreatedNoCurves() {
        var raw = List.of(p(-60,0),p(0,0),p(0,-60),p(80,-60),p(140,-60));
        var stops = List.of(new Stop("first",1,"first","UP",true,p(-50,0)),
                new Stop("turn",2,"turn","UP",true,p(0,-10)),
                new Stop("after",3,"after","DOWN",true,p(100,-60)),
                new Stop("last",4,"last","DOWN",true,p(130,-60)));
        var typed = new ArrayList<RouteShapeEvidence.TypedPoint>();
        for (String direction : List.of("UP","DOWN")) {
            for (int i=direction.equals("UP")?0:1; i<=(direction.equals("UP")?1:raw.size()-1); i++) {
                var point=raw.get(i);
                typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(point.x(),point.y()),i<=1||i==raw.size()-1?1:3,direction,false));
            }
        }
        var evidence=new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","a".repeat(64))),typed);
        var g=new RouteGeometry("987654321",1,"only turn","A","B",2,stops,new Shape(new UUID(0,2),Instant.EPOCH,raw,evidence));
        var baseline=AutomaticRouteDisplay.sourceOnly(g,RouteDisplayGeneration.input(g).digest());
        var ordinary=SourceBoundDisplayCurves.build(g,baseline.paths(),baseline.connections());
        assertThat(ordinary.curveCount()).isZero();
        assertThat(AnchorPreservingDisplayCurves.build(g,ordinary,baseline.connections()).anchoredCornerCount()).isZero();
        var built=AutomaticRouteDisplay.buildDetailed(g,RouteDisplayGeneration.input(g).digest());
        assertThat(built.appliedCurves()).isPositive();
        assertThat(built.selectedStage()).isEqualTo("SOURCE_BOUND_CURVE");
        assertThat(built.geometry().connections()).containsKeys(1,2,3,4);
    }

    @Test void turnFilletInterpolationFailureRetainsTheAlreadyValidatedCurves() {
        double scale=111320*Math.cos(Math.toRadians(1)),q=12/scale;
        var raw=List.of(new Point(-7*q,1),new Point(q,1),new Point(q,1+80d/111320),new Point(3*q,1+80d/111320));
        var stops=List.of(new Stop("first",1,"first","UP",true,raw.getFirst()),
                new Stop("turn",2,"turn","UP",true,new Point(-3*q,1)),
                new Stop("last",3,"last","DOWN",true,raw.getLast()));
        var typed=new ArrayList<RouteShapeEvidence.TypedPoint>();
        for(String direction:List.of("UP","DOWN"))for(int i=0;i<=(direction.equals("UP")?0:raw.size()-1);i++) {
            var point=raw.get(i);
            typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(point.x(),point.y()),i==0||i==raw.size()-1?1:3,direction,false));
        }
        var evidence=new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","a".repeat(64))),typed);
        var g=new RouteGeometry("123456789",1,"zero meridian","A","B",2,stops,new Shape(new UUID(0,3),Instant.EPOCH,raw,evidence));
        var input=RouteDisplayGeneration.input(g);
        var baseline=AutomaticRouteDisplay.sourceOnly(g,input.digest());
        var ordinary=SourceBoundDisplayCurves.build(g,baseline.paths(),baseline.connections());
        var previous=AnchorPreservingDisplayCurves.build(g,ordinary,baseline.connections()).plan();
        assertThat(previous.curveCount()).isPositive();
        assertThatThrownBy(()->SourceBoundDisplayCurves.withTurnSupport(g,previous,baseline.connections()))
                .isInstanceOf(IllegalArgumentException.class);
        var built=AutomaticRouteDisplay.buildDetailed(g,input.digest());
        assertThat(built.geometry().paths()).isEqualTo(previous.paths());
        assertThat(built.appliedCurves()).isEqualTo(previous.curveCount());
        assertThat(built.selectedStage()).isEqualTo("SOURCE_BOUND_CURVE");
        assertThat(built.geometry().connections()).containsKeys(1,2,3);
    }
}
