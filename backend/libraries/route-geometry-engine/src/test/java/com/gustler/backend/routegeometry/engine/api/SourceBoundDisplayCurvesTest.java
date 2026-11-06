package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceBoundDisplayCurvesTest {
    private static Point p(double x,double y){return new Point(127+x/(111320*Math.cos(Math.toRadians(37))),37+y/111320);}
    private static RouteGeometry geometry(List<Point> raw,int turnIndex,List<Integer> stopIndices,Set<Integer> controls,Set<Integer> unmatched,boolean evidence) {
        var stops=new ArrayList<Stop>();
        for(int i=0;i<stopIndices.size();i++){int at=stopIndices.get(i);stops.add(new Stop("station:"+i,i+1,"stop",at<=turnIndex?"UP":"DOWN",true,raw.get(at)));}
        int turnSequence=0;for(int i=0;i<stopIndices.size();i++)if(stopIndices.get(i)==turnIndex)turnSequence=i+1;
        var typed=new ArrayList<RouteShapeEvidence.TypedPoint>();
        for(String direction:List.of("UP","DOWN"))for(int i=direction.equals("UP")?0:turnIndex;i<=(direction.equals("UP")?turnIndex:raw.size()-1);i++) {
            var point=raw.get(i);var web=new RouteDisplayInput.Point(point.x()+(unmatched.contains(i)?.001:0),point.y());
            typed.add(new RouteShapeEvidence.TypedPoint(web,stopIndices.contains(i)?1:controls.contains(i)?2:3,direction,true));
        }
        var proof=evidence?new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","a".repeat(64))),typed):RouteShapeEvidence.EMPTY;
        return new RouteGeometry("synthetic-source",1,"route","A","B",turnSequence,stops,
                new Shape(new UUID(0,1),Instant.EPOCH,raw,proof));
    }
    private static RouteGeometry rightAngle(boolean evidence) {
        return geometry(List.of(p(-80,0),p(0,0),p(0,80),p(40,80),p(40,-40),p(-80,0)),3,List.of(0,3,5),Set.of(),Set.of(),evidence);
    }

    @Test void boundedCurveUsesExplicitMonotoneNodeReferenceAndPreservesAllSourceAndVisitData() {
        var source=rightAngle(true);var original=source.points();var stops=source.busStops();
        var details=AutomaticRouteDisplay.buildDetailed(source,"fixture");
        assertThat(details.selectedStage()).isEqualTo("SOURCE_BOUND_CURVE");assertThat(details.appliedCurves()).isPositive();
        assertThat(source.points()).isEqualTo(original);assertThat(source.busStops()).isEqualTo(stops);
        assertThat(details.geometry().sourcePaths()).isEqualTo(source.paths());
        assertThat(details.geometry().connections()).containsKeys(1,2,3);
        var plan=details.curveCandidate();
        assertThat(plan.referenceDomain()).contains("NODE_BASELINE").contains("NOT_ORIGINAL_RAW");
        assertThat(plan.maximumCertifiedDeviationMetres()).isLessThanOrEqualTo(2);
        assertThat(plan.continuousControlHullCertified()).isTrue();assertThat(plan.serializedChordCorrespondenceCertified()).isTrue();
        assertThat(plan.curvature().values()).allSatisfy(c->assertThat(c.noNewFixedFiveMetreRegression()).isTrue());
        assertThat(SourceBoundDisplayCurves.endpointsPreserved(source.paths(),details.geometry().paths())).isTrue();
        for(var entry:plan.correspondences().entrySet()) {
            var map=entry.getValue();assertThat(map.displayPoints()).hasSameSizeAs(map.referenceProgressAtDisplayVertices());
            for(int i=1;i<map.referenceProgressAtDisplayVertices().size();i++)assertThat(map.referenceProgressAtDisplayVertices().get(i)).isGreaterThan(map.referenceProgressAtDisplayVertices().get(i-1));
            double[] referenceChain=new double[map.referencePoints().size()];var frame=map.metricFrame();
            for(int i=1;i<referenceChain.length;i++)referenceChain[i]=referenceChain[i-1]+metricDistance(map.referencePoints().get(i-1),map.referencePoints().get(i),frame);
            for(int i=0;i<map.displayPoints().size()-1;i++)for(double fraction:new double[]{0,.5,1}) {
                double s=map.referenceProgressAtDisplayVertices().get(i)+fraction*(map.referenceProgressAtDisplayVertices().get(i+1)-map.referenceProgressAtDisplayVertices().get(i));
                int segment=0;while(segment+1<referenceChain.length-1 && referenceChain[segment+1]<s)segment++;
                double sourceFraction=(s-referenceChain[segment])/(referenceChain[segment+1]-referenceChain[segment]);
                var actual=RouteStopAlignment.interpolate(map.displayPoints().get(i),map.displayPoints().get(i+1),fraction);
                var expected=RouteStopAlignment.interpolate(map.referencePoints().get(segment),map.referencePoints().get(segment+1),sourceFraction);
                assertThat(metricDistance(actual,expected,frame)).isLessThanOrEqualTo(2);
            }
        }
        for(var connection:details.geometry().connections().values()) {
            // Connections belong to the final serialized array, not the old sparse source.
            boolean onEither=List.of(details.geometry().paths().up(),details.geometry().paths().down()).stream().anyMatch(path->{
                int i=connection.segmentIndex();if(i<0 || i+1>=path.points().size())return false;
                var a=path.points().get(i);var b=path.points().get(i+1);double f=connection.segmentFraction();
                return Math.abs(connection.point().x()-(a.x()+f*(b.x()-a.x())))<1e-12 && Math.abs(connection.point().y()-(a.y()+f*(b.y()-a.y())))<1e-12;
            });assertThat(onEither).isTrue();
        }
    }

    @Test void structuralPassWithNoEvidenceDoesNotClaimAnAutomaticDisplayCorrection() {
        var source=rightAngle(false);var details=AutomaticRouteDisplay.buildDetailed(source,"fixture");
        assertThat(details.appliedRolePointRemovals()).isZero();assertThat(details.appliedCurves()).isZero();
        assertThat(details.displayCorrectionApplied()).isFalse();assertThat(details.curveCandidate().curveCount()).isZero();
        assertThat(details.geometry().sourcePaths()).isEqualTo(source.paths());
    }

    @Test void typeOneRoadAnchorRemainsProtectedEvenWhenPassengerGpsIsFartherThanEightMetres() {
        var base=geometry(List.of(p(-80,0),p(0,0),p(0,80),p(40,80),p(40,-40),p(-80,0)),3,List.of(0,1,3,5),Set.of(),Set.of(),true);
        var stops=new ArrayList<>(base.busStops());var before=stops.get(1);
        stops.set(1,new Stop(before.stationId(),before.stopSequence(),before.name(),before.direction(),before.boardingAllowed(),p(0,-20)));
        var source=new RouteGeometry(base.routeId(),base.referenceVersionId(),base.name(),base.startPoint(),base.endPoint(),base.turnSequence(),stops,base.shape());
        var details=AutomaticRouteDisplay.buildDetailed(source,"fixture");
        assertThat(details.geometry().paths().up().points()).contains(p(0,0));
        assertThat(details.geometry().connections()).containsKey(2);
        assertThat(details.geometry().connections().get(2).point().x()).isCloseTo(p(0,0).x(),within(1e-10));
    }

    @Test void realSharedShapeIsPreservedButCompleteTypeTwoGroupCanBeRemovedAtomically() {
        var a=p(-80,0);var b=p(0,0);var c=p(0,80);var turn=p(40,80);
        var source=geometry(List.of(a,b,c,turn,c,p(-30,40),a),3,List.of(0,3,6),Set.of(),Set.of(),true);
        var details=AutomaticRouteDisplay.buildDetailed(source,"fixture");
        assertThat(details.geometry().paths().up().points()).contains(c);
        assertThat(details.geometry().paths().down().points()).contains(c);
        assertThat(details.appliedRolePointRemovals()).isZero();
        var control=p(0,3);
        var logical=geometry(List.of(p(-30,0),p(-10,0),control,p(10,0),p(30,0),p(10,-1),control,p(-10,-1),p(-30,-1)),
                4,List.of(0,4,8),Set.of(2,6),Set.of(),true);
        var cleaned=EvidenceRoutePaths.build(logical);
        assertThat(cleaned.up().points()).doesNotContain(control);assertThat(cleaned.down().points()).doesNotContain(control);
        assertThat(logical.points().stream().filter(control::equals).count()).isEqualTo(2);
        assertThat(RouteDisplayTopology.noNewCrossings(logical.paths(),cleaned)).isTrue();
        assertThat(RouteDisplayTopology.noNewContacts(logical.paths(),cleaned)).isTrue();
    }

    @Test void failedCurveRollsBackToTheValidatedNodeBundleInsteadOfRevivingRemovedSourceNodes() {
        var raw=new ArrayList<>(List.of(p(-80,0),p(-60,5),p(-40,0),p(0,0),p(0,80),p(40,80),p(0,80),p(-.5,.5),p(-80,20)));
        for(int i=1;i<=25;i++)raw.add(p(-80,20-i*20.0/25));
        var source=geometry(raw,5,List.of(0,5,raw.size()-1),Set.of(1),Set.of(7),true);
        var details=AutomaticRouteDisplay.buildDetailed(source,"fixture");
        assertThat(details.curveCandidate()).isNotNull();assertThat(details.curveCandidate().curveCount()).isPositive();
        assertThat(details.appliedCurves()).isZero();assertThat(details.selectedStage()).isEqualTo("VALIDATED_NODE_BASELINE_CURVE_REJECTED");
        assertThat(details.appliedRolePointRemovals()).isPositive();assertThat(details.geometry().paths().up().points()).doesNotContain(p(-60,5));
        assertThat(details.curveRejectionReasons()).anyMatch(reason->reason.contains("CROSSING") || reason.contains("CONTACT"));
        assertThat(source.points()).isEqualTo(raw);
    }

    @Test void successfulSequentialFloorCannotBeReplacedByAnAtomicPolicyThatRevivesAnOldTouch() {
        var shared=p(0,0);
        var source=geometry(List.of(p(-30,0),shared,p(30,0),p(30,30),p(10,2),shared,p(-10,2),p(-30,2)),
                3,List.of(0,3,7),Set.of(5),Set.of(),true);
        var floor=EvidenceRoutePaths.buildSequentialFloor(source);
        assertThat(floor.up().points()).contains(shared);
        assertThat(floor.down().points()).doesNotContain(shared);
        assertThat(RouteDisplayTopology.noNewContacts(floor,source.paths())).isFalse();
        var proposed=EvidenceRoutePaths.build(source);
        assertThat(RouteDisplayTopology.noNewContacts(floor,proposed)).isTrue();
        assertThat(proposed.down().points()).doesNotContain(shared);
        var finalBundle=AutomaticRouteDisplay.buildDetailed(source,"fixture");
        assertThat(finalBundle.geometry().paths()).isNotNull();
        assertThat(RouteDisplayTopology.noNewContacts(floor,finalBundle.geometry().paths())).isTrue();
        assertThat(source.points()).contains(shared);
    }

    @Test void properOnlyCrossingGuardDoesNotHideANewNonAdjacentEndpointTouchOrOverlap() {
        var baseline=new RoutePaths(new RoutePaths.Path(List.of(p(-20,0),p(20,0))),new RoutePaths.Path(List.of(p(0,10),p(20,10))));
        var touch=new RoutePaths(baseline.up(),new RoutePaths.Path(List.of(p(0,10),p(0,0),p(20,10))));
        assertThat(RouteDisplayTopology.noNewCrossings(baseline,touch)).isTrue();
        assertThat(RouteDisplayTopology.noNewContacts(baseline,touch)).isFalse();
        var overlap=new RoutePaths(baseline.up(),new RoutePaths.Path(List.of(p(-10,0),p(10,0))));
        assertThat(RouteDisplayTopology.noNewContacts(baseline,overlap)).isFalse();
        assertThat(RouteDisplayTopology.noNewContacts(touch,touch)).isTrue();
    }

    @Test void ordinaryAdjacentLinksAndPreservedTurnEndpointAreNotFalseNewContacts() {
        var a=p(-20,0);var b=p(0,0);var turn=p(20,0);
        var baseline=new RoutePaths(new RoutePaths.Path(List.of(a,b,turn)),new RoutePaths.Path(List.of(turn,p(20,20),p(-20,20))));
        var subdivided=new RoutePaths(new RoutePaths.Path(List.of(a,p(-10,0),b,p(10,0),turn)),baseline.down());
        assertThat(RouteDisplayTopology.noNewContacts(baseline,subdivided)).isTrue();
    }

    @Test void strictOfficialVisibleSubsequenceAddsShapeRolesWithoutDeletingMoreSourcePoints() {
        var full=geometry(List.of(p(-80,0),p(-60,5),p(-40,0),p(0,0),p(0,80),p(40,80),p(40,-40),p(-80,0)),5,List.of(0,5,7),Set.of(1),Set.of(),true);
        var visible=new ArrayList<Point>();for(var typed:full.shape().evidence().webPoints())if(typed.type()!=2)visible.add(new Point(typed.point().x(),typed.point().y()));
        var source=new RouteGeometry(full.routeId(),full.referenceVersionId(),full.name(),full.startPoint(),full.endPoint(),full.turnSequence(),full.busStops(),new Shape(full.shape().id(),Instant.EPOCH,visible,full.shape().evidence()));
        var classified=EvidenceRoutePaths.classify(source);
        assertThat(classified.normalization()).isEqualTo("OFFICIAL_VISIBLE_SUBSEQUENCE_V1");assertThat(classified.matchedPoints()).isEqualTo(visible.size());
        assertThat(EvidenceRoutePaths.build(source)).isEqualTo(source.paths());
        var details=AutomaticRouteDisplay.buildDetailed(source,"fixture");assertThat(details.appliedRolePointRemovals()).isZero();assertThat(details.appliedCurves()).isPositive();
        assertThat(source.points()).isEqualTo(visible);
    }

    @Test void visibleSubsequenceRejectsCoordinateDriftReorderingAndVisitMismatchWithoutNearestMatching() {
        var full=geometry(List.of(p(-80,0),p(-60,5),p(-40,0),p(0,0),p(0,80),p(40,80),p(40,-40),p(-80,0)),5,List.of(0,5,7),Set.of(1),Set.of(),true);
        var visible=new ArrayList<Point>();for(var typed:full.shape().evidence().webPoints())if(typed.type()!=2)visible.add(new Point(typed.point().x(),typed.point().y()));
        visible.set(1,p(-40,20));
        var bad=new RouteGeometry(full.routeId(),full.referenceVersionId(),full.name(),full.startPoint(),full.endPoint(),full.turnSequence(),full.busStops(),new Shape(full.shape().id(),Instant.EPOCH,visible,full.shape().evidence()));
        assertThat(EvidenceRoutePaths.classify(bad).classifications()).isEmpty();
        var reordered=new ArrayList<>(visible);Collections.swap(reordered,2,3);
        var wrong=new RouteGeometry(full.routeId(),full.referenceVersionId(),full.name(),full.startPoint(),full.endPoint(),full.turnSequence(),full.busStops().subList(1,3),new Shape(full.shape().id(),Instant.EPOCH,reordered,full.shape().evidence()));
        assertThat(EvidenceRoutePaths.classify(wrong).classifications()).isEmpty();
    }
    private static double metricDistance(Point a,Point b,SourceBoundDisplayCurves.Frame frame) {
        return Math.hypot((a.x()-b.x())*frame.xMetresPerDegree(),(a.y()-b.y())*frame.yMetresPerDegree());
    }
}
