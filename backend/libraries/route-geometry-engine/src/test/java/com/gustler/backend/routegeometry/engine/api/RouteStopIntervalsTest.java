package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Stop;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RouteStopIntervalsTest {
    private static Point p(double x,double y) { return new Point(127+x/(111320*Math.cos(Math.toRadians(37))),37+y/111320); }
    private static Stop stop(int sequence,String direction,Point point) { return new Stop("station:"+sequence,sequence,"stop",direction,true,point); }
    private static RoutePaths paths(List<Point> up,List<Point> down) { return new RoutePaths(new RoutePaths.Path(up),new RoutePaths.Path(down)); }
    private static Connection c(List<Point> points,int segment,double fraction) {
        var q=RouteStopAlignment.interpolate(points.get(segment),points.get(segment+1),fraction);
        return new Connection(new RouteDisplayInput.Point(q.x(),q.y()),segment,fraction);
    }
    private static final SourceBoundDisplayCurves.Frame FRAME=new SourceBoundDisplayCurves.Frame(127,37,111320*Math.cos(Math.toRadians(37)),111320,37,37.01);
    private static List<Double> chain(List<Point> points) {
        List<Double> values=new ArrayList<>();values.add(0.0);
        for(int i=1;i<points.size();i++)values.add(values.getLast()+Math.hypot((points.get(i).x()-points.get(i-1).x())*FRAME.xMetresPerDegree(),(points.get(i).y()-points.get(i-1).y())*FRAME.yMetresPerDegree()));
        return values;
    }
    private static SourceBoundDisplayCurves.Plan plan(RoutePaths reference,RoutePaths candidate,List<Double> upMapping) {
        var up=new SourceBoundDisplayCurves.Correspondence(reference.up().points(),candidate.up().points(),upMapping,chain(reference.up().points()).getLast(),FRAME);
        var down=new SourceBoundDisplayCurves.Correspondence(reference.down().points(),candidate.down().points(),chain(reference.down().points()),chain(reference.down().points()).getLast(),FRAME);
        return new SourceBoundDisplayCurves.Plan("VALIDATED_NODE_BASELINE",candidate,Map.of("UP",up,"DOWN",down),Map.of(),1,1,true,true,List.of());
    }

    @Test void extractsAllEightyFourPairsFromFinalConnectionIndicesAndIncludesBothTurnLegs() {
        var paths=paths(List.of(p(-100,0),p(100,0)),List.of(p(100,0),p(-100,0)));
        List<Stop> stops=new ArrayList<>();Map<Integer,Connection> connections=new HashMap<>();
        for(int i=0;i<43;i++) {double fraction=.05+.9*i/42;stops.add(stop(i+1,"UP",RouteStopAlignment.interpolate(paths.up().points().getFirst(),paths.up().points().getLast(),fraction)));connections.put(i+1,c(paths.up().points(),0,fraction));}
        for(int i=0;i<42;i++) {double fraction=.05+.9*i/41;stops.add(stop(i+44,"DOWN",RouteStopAlignment.interpolate(paths.down().points().getFirst(),paths.down().points().getLast(),fraction)));connections.put(i+44,c(paths.down().points(),0,fraction));}
        var result=RouteStopIntervals.evaluate(stops,paths,connections);
        assertThat(result.usable()).isTrue();assertThat(result.intervals()).hasSize(84);
        assertThat(result.intervals().stream().filter(i->!i.turnSeam() && i.legs().getFirst().direction().equals("UP"))).hasSize(42);
        assertThat(result.intervals().stream().filter(i->!i.turnSeam() && i.legs().getFirst().direction().equals("DOWN"))).hasSize(41);
        var turn=result.intervals().get(42);assertThat(turn.turnSeam()).isTrue();assertThat(turn.fromSequence()).isEqualTo(43);assertThat(turn.toSequence()).isEqualTo(44);
        assertThat(turn.legs()).hasSize(2);assertThat(turn.legs().getFirst().lengthMetres()).isCloseTo(10,within(1e-6));
        assertThat(turn.legs().getLast().lengthMetres()).isCloseTo(10,within(1e-6));assertThat(turn.pathLengthMetres()).isCloseTo(20,within(1e-6));
    }

    @Test void rejectsMissingOutOfRangeAndOffSegmentSerializedConnections() {
        var path=paths(List.of(p(0,0),p(100,0)),List.of(p(100,0),p(0,-10)));var visits=List.of(stop(1,"UP",p(10,0)));
        assertThat(RouteStopIntervals.evaluate(visits,path,Map.of()).blockingIssues()).extracting(i->i.code()).contains("STOP_INTERVAL_CONNECTION_MISSING");
        var outside=new Connection(new RouteDisplayInput.Point(p(10,0).x(),p(10,0).y()),99,.1);
        assertThat(RouteStopIntervals.evaluate(visits,path,Map.of(1,outside)).blockingIssues()).extracting(i->i.code()).contains("STOP_INTERVAL_CONNECTION_INVALID");
        var overflow=new Connection(new RouteDisplayInput.Point(p(10,0).x(),p(10,0).y()),Integer.MAX_VALUE,.1);
        assertThat(RouteStopIntervals.evaluate(visits,path,Map.of(1,overflow)).blockingIssues()).extracting(i->i.code()).contains("STOP_INTERVAL_CONNECTION_INVALID");
        var wrong=new Connection(new RouteDisplayInput.Point(p(10,1).x(),p(10,1).y()),0,.1);
        assertThat(RouteStopIntervals.evaluate(visits,path,Map.of(1,wrong)).blockingIssues()).extracting(i->i.code()).contains("STOP_INTERVAL_CONNECTION_INVALID");
    }

    @Test void rejectsReverseProgressAndDisconnectedTurnWithoutJoiningAcrossAGap() {
        var path=paths(List.of(p(0,0),p(100,0)),List.of(p(100,1),p(0,-10)));
        var reverse=RouteStopIntervals.evaluate(List.of(stop(1,"UP",p(80,0)),stop(2,"UP",p(20,0))),path,Map.of(1,c(path.up().points(),0,.8),2,c(path.up().points(),0,.2)));
        assertThat(reverse.blockingIssues()).extracting(i->i.code()).contains("STOP_INTERVAL_REVERSES");
        var seam=RouteStopIntervals.evaluate(List.of(stop(1,"UP",p(80,0)),stop(2,"DOWN",p(80,0))),path,Map.of(1,c(path.up().points(),0,.8),2,c(path.down().points(),0,.2)));
        assertThat(seam.blockingIssues()).extracting(i->i.code()).contains("STOP_INTERVAL_TURN_SEAM_DISCONNECTED");
        assertThat(seam.intervals().getFirst().pathLengthMetres()).isNull();assertThat(seam.intervals().getFirst().legs()).isEmpty();
    }

    @Test void sharedProjectionIsOnlyADiagnosticWithoutAnIndependentEarlierOccurrence() {
        var path=paths(List.of(p(0,0),p(100,0)),List.of(p(100,0),p(0,-10)));
        var result=RouteStopIntervals.evaluate(List.of(stop(1,"UP",p(0,20)),stop(2,"UP",p(0,-20))),path,Map.of(1,c(path.up().points(),0,0),2,c(path.up().points(),0,0)));
        assertThat(result.usable()).isTrue();assertThat(result.diagnostics()).extracting(i->i.code()).containsExactly("STOP_INTERVAL_DISTANT_VISITS_SHARE_PROJECTION");
    }

    @Test void detourRatioAndStopGapRemainMeasurementsInsteadOfInventedHardFailures() {
        var path=paths(List.of(p(0,0),p(0,100),p(100,100),p(100,0),p(1,0)),List.of(p(1,0),p(-10,-10)));
        var result=RouteStopIntervals.evaluate(List.of(stop(1,"UP",p(0,-24)),stop(2,"UP",p(1,-24))),path,Map.of(1,c(path.up().points(),0,0),2,c(path.up().points(),3,1)));
        assertThat(result.usable()).isTrue();assertThat(result.intervals().getFirst().pathToStopStraightRatio()).isGreaterThan(300);
        assertThat(result.intervals().getFirst().fromStopGapMetres()).isCloseTo(24,within(1e-6));
    }

    @Test void missingSourceCoordinateRemainsExplicitlyInsufficientWithoutChangingExistingPolicy() {
        var path=paths(List.of(p(0,0),p(100,0)),List.of(p(100,0),p(0,-10)));
        var result=RouteStopIntervals.evaluate(List.of(stop(1,"UP",null),stop(2,"UP",p(20,0))),path,Map.of(2,c(path.up().points(),0,.2)));
        assertThat(result.usable()).isTrue();assertThat(result.intervals()).hasSize(1);assertThat(result.intervals().getFirst().pathLengthMetres()).isNull();
        assertThat(result.diagnostics()).extracting(i->i.code()).containsExactly("STOP_INTERVAL_SOURCE_COORDINATE_UNAVAILABLE");
    }

    @Test void rejectsADifferentLapWithTheSameCoordinatesAndUnchangedGpsGap() {
        var reference=paths(List.of(p(0,0),p(20,0),p(0,0),p(0,20)),List.of(p(0,20),p(-20,20)));
        var visits=List.of(stop(1,"UP",p(0,-20)));var before=Map.of(1,c(reference.up().points(),2,0));var after=Map.of(1,c(reference.up().points(),0,0));
        assertThat(before.get(1).point()).isEqualTo(after.get(1).point());
        var check=RouteStopIntervals.preservedCurveOccurrences(visits,reference,before,reference,after,plan(reference,reference,chain(reference.up().points())));
        assertThat(check.usable()).isFalse();assertThat(check.blockingIssues()).extracting(i->i.code()).contains("CURVE_STOP_SOURCE_OCCURRENCE_CHANGED");
    }

    @Test void acceptsCentimetreArithmeticNoiseButRejectsSourceOccurrenceMovementBeyondTwoCentimetres() {
        var reference=paths(List.of(p(0,0),p(100,0)),List.of(p(100,0),p(0,-10)));var visits=List.of(stop(1,"UP",p(50,20)));var before=Map.of(1,c(reference.up().points(),0,.5));var plan=plan(reference,reference,chain(reference.up().points()));
        assertThat(RouteStopIntervals.preservedCurveOccurrences(visits,reference,before,reference,Map.of(1,c(reference.up().points(),0,.5001)),plan).usable()).isTrue();
        assertThat(RouteStopIntervals.preservedCurveOccurrences(visits,reference,before,reference,Map.of(1,c(reference.up().points(),0,.5003)),plan).usable()).isFalse();
    }

    @Test void rejectsCollapseOfAPreviouslyPositiveIntervalEvenInsideThePerStopNoiseAllowance() {
        var reference=paths(List.of(p(0,0),p(100,0)),List.of(p(100,0),p(0,-10)));var visits=List.of(stop(1,"UP",p(0,0)),stop(2,"UP",p(.01,0)));var before=Map.of(1,c(reference.up().points(),0,0),2,c(reference.up().points(),0,.0001));var after=Map.of(1,c(reference.up().points(),0,0),2,c(reference.up().points(),0,0));
        var check=RouteStopIntervals.preservedCurveOccurrences(visits,reference,before,reference,after,plan(reference,reference,chain(reference.up().points())));
        assertThat(check.blockingIssues()).extracting(i->i.code()).contains("CURVE_STOP_INTERVAL_COLLAPSED");
        assertThat(check.blockingIssues()).extracting(i->i.code()).doesNotContain("CURVE_STOP_SOURCE_OCCURRENCE_CHANGED");
    }

    @Test void transportsTheLiteralRoadKnotInsteadOfChoosingACloserCurveProjectionToTheStopPin() {
        var reference=paths(List.of(p(0,0),p(20,0),p(40,0)),List.of(p(40,0),p(40,-40)));
        var candidate=paths(List.of(p(0,0),p(19,.5),p(20,0),p(40,0)),reference.down().points());var visit=stop(1,"UP",p(20,24));var before=Map.of(1,c(reference.up().points(),0,1));
        var map=List.of(0.0,chain(reference.up().points()).get(1)*.95,chain(reference.up().points()).get(1),chain(reference.up().points()).getLast());var plan=plan(reference,candidate,map);
        var unconstrained=RouteStopAlignment.align(candidate.up().points(),List.of(visit),false).connections().get(1);
        assertThat(unconstrained.point()).isNotEqualTo(before.get(1).point());
        var transported=RouteStopIntervals.preserveCurveConnections(List.of(visit),reference,before,plan);
        assertThat(transported.usable()).isTrue();assertThat(transported.connections().get(1).point()).isEqualTo(before.get(1).point());
        assertThat(transported.connections().get(1).segmentIndex()).isEqualTo(2);assertThat(transported.connections().get(1).segmentFraction()).isZero();
        assertThat(RouteStopIntervals.preservedCurveOccurrences(List.of(visit),reference,before,candidate,transported.connections(),plan).usable()).isTrue();
    }

    @Test void aRejectedAnchorLayerKeepsOnlyAnIndependentlyValidatedOrdinaryCurveFloor() {
        var raw=List.of(p(-80,0),p(0,0),p(0,80),p(40,80),p(40,-40),p(-80,0));
        var stops=List.of(stop(1,"UP",raw.getFirst()),stop(2,"UP",raw.get(3)),stop(3,"DOWN",raw.getLast()));
        var typed=new ArrayList<RouteShapeEvidence.TypedPoint>();
        for(String direction:List.of("UP","DOWN"))for(int i=direction.equals("UP")?0:3;i<=(direction.equals("UP")?3:5);i++) {
            var q=raw.get(i);typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(q.x(),q.y()),i==0||i==3||i==5?1:3,direction,false));
        }
        var evidence=new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","a".repeat(64))),typed);
        var source=new RouteGeometry("synthetic",1,"route","A","B",2,stops,new RouteGeometry.Shape(new UUID(0,1),Instant.EPOCH,raw,evidence));
        var baseline=AutomaticRouteDisplay.sourceOnly(source,"input");
        var ordinary=SourceBoundDisplayCurves.build(source,baseline.paths(),baseline.connections());assertThat(ordinary.curveCount()).isPositive();
        var invalid=new SourceBoundDisplayCurves.Plan(ordinary.referenceDomain(),ordinary.paths(),ordinary.correspondences(),ordinary.curvature(),ordinary.curveCount()+1,
                ordinary.maximumCertifiedDeviationMetres(),false,ordinary.serializedChordCorrespondenceCertified(),ordinary.notes());
        var retained=AutomaticRouteDisplay.selectCurveFloor(source,"input",baseline,ordinary,invalid,true);
        assertThat(retained.geometry()).isNotNull();assertThat(retained.geometry().paths()).isEqualTo(ordinary.paths()).isNotEqualTo(baseline.paths());
        assertThat(retained.plan().curveCount()).isEqualTo(ordinary.curveCount());
        assertThat(retained.plan().notes()).contains("ANCHOR_FINAL_GATE_REJECTED:CURVE_SOURCE_BOUND_NOT_CERTIFIED");
        assertThat(AutomaticRouteDisplay.selectCurveFloor(source,"input",baseline,invalid,invalid,true).geometry()).isNull();
        assertThat(AutomaticRouteDisplay.selectCurveFloor(source,"input",baseline,invalid,ordinary,true).geometry()).isNotNull();
    }
}
