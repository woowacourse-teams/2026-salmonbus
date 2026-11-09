package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.assertThat;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence.TypedPoint;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PartialVisibleEvidenceTest {
    private static Point p(double x,double y){return new Point(127+x/(111320*Math.cos(Math.toRadians(37))),37+y/111320);}
    private static RouteGeometry original(){
        var points=new ArrayList<Point>();
        for(int i=0;i<25;i++)points.add(p(20*i,i%2*8));
        points.add(points.getLast());
        for(int i=1;i<25;i++)points.add(p(480-20*i,100+i%2*8));
        var roles=new ArrayList<TypedPoint>();
        for(int i=0;i<points.size();i++) {
            var q=points.get(i);
            roles.add(new TypedPoint(new RouteDisplayInput.Point(q.x(),q.y()),Set.of(0,24,25,49).contains(i)?1:3,i<=24?"UP":"DOWN",false));
            if(i==0)roles.add(new TypedPoint(new RouteDisplayInput.Point(p(5,25).x(),p(5,25).y()),2,"UP",true));
        }
        var stops=List.of(new Stop("A",1,"A","UP",true,points.getFirst()),new Stop("B",2,"B","UP",true,points.get(24)),new Stop("C",3,"C","DOWN",true,points.getLast()));
        return new RouteGeometry("204000057",1,"partial","A","B",2,stops,new Shape(new UUID(0,1),Instant.EPOCH,points,
                new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","b".repeat(64))),roles),"a".repeat(64)));
    }
    private static RouteGeometry changed(RouteGeometry g,List<Point> points,List<TypedPoint> roles) {
        return new RouteGeometry(g.routeId(),g.referenceVersionId(),g.name(),g.startPoint(),g.endPoint(),g.turnSequence(),g.busStops(),
                new Shape(g.shape().id(),g.shape().receivedAt(),points,new RouteShapeEvidence(g.shape().evidence().selectedSource(),g.shape().evidence().sources(),roles),g.shape().sourceManifestId()));
    }
    private static RouteGeometry drift(int... indices){
        var g=original();var points=new ArrayList<>(g.points());
        for(int i:indices){var q=points.get(i);points.set(i,new Point(q.x(),q.y()+18/111320d));}
        return changed(g,points,g.shape().evidence().webPoints());
    }
    @Test void isolatedShapeDriftKeepsItsSegmentsAndStillCurvesTrustedRegions(){
        var g=drift(10);var raw=List.copyOf(g.points());var classified=EvidenceRoutePaths.classify(g);
        assertThat(classified.normalization()).isEqualTo("PARTIAL_VISIBLE_SUBSEQUENCE_V1");
        assertThat(classified.matchedPoints()).isEqualTo(49);assertThat(classified.unmatchedPoints()).isEqualTo(1);
        assertThat(classified.classifications()).doesNotContainKeys(raw.get(9),raw.get(10),raw.get(11));
        var result=AutomaticRouteDisplay.buildDetailed(g,RouteDisplayGeneration.input(g).digest());
        assertThat(result.appliedCurves()).isPositive();assertThat(result.geometry().connections()).hasSize(3);
        assertThat(result.geometry().paths().up().points()).containsSequence(raw.subList(8,13));
        assertThat(RouteDisplayTopology.noNewContacts(g.paths(),result.geometry().paths())).isTrue();
        assertThat(result.geometry().quality().issues()).anyMatch(i->i.code().equals("PARTIAL_SOURCE_ROLE_MATCH"));
        assertThat(g.points()).isEqualTo(raw);
    }
    @Test void completeVisibleEvidenceKeepsItsExistingClassification(){
        var g=original();var c=EvidenceRoutePaths.classify(g);
        assertThat(c.normalization()).isEqualTo("OFFICIAL_VISIBLE_SUBSEQUENCE_V1");assertThat(c.unmatchedPoints()).isZero();
        assertThat(AutomaticRouteDisplay.buildDetailed(g,"complete").appliedCurves()).isPositive();
    }
    @Test void broadMismatchStillRejectsVisibleRoles(){
        assertThat(EvidenceRoutePaths.classify(drift(8,10,12)).classifications()).isEmpty();
        assertThat(EvidenceRoutePaths.classify(drift(10,11)).unmatchedPoints()).isEqualTo(2);
    }
    @Test void stationOrTurnMismatchCannotBeTreatedAsOrdinaryShapeDrift(){
        for(int index:List.of(0,24,25,49))assertThat(EvidenceRoutePaths.classify(drift(index)).classifications()).isEmpty();
    }
    @Test void invalidDirectionOrderingStillRejectsPartialEvidence(){
        var g=drift(10);var roles=new ArrayList<>(g.shape().evidence().webPoints());var old=roles.get(6);
        roles.set(6,new TypedPoint(old.point(),old.type(),"DOWN",false));
        assertThat(EvidenceRoutePaths.classify(changed(g,g.points(),roles)).classifications()).isEmpty();
    }
    @Test void changedPointCountDoesNotInventInsertionOrDeletionMatching(){
        var g=drift(10);var points=new ArrayList<>(g.points());points.add(12,p(999,999));
        assertThat(EvidenceRoutePaths.classify(changed(g,points,g.shape().evidence().webPoints())).classifications()).isEmpty();
        points=new ArrayList<>(g.points());points.remove(12);
        assertThat(EvidenceRoutePaths.classify(changed(g,points,g.shape().evidence().webPoints())).classifications()).isEmpty();
    }
    @Test void oneUnmatchedOccurrenceProtectsEveryOccurrenceOfThatCoordinate(){
        var g=drift(10);var points=new ArrayList<>(g.points());var roles=new ArrayList<>(g.shape().evidence().webPoints());
        points.set(15,points.get(10));var old=roles.get(16);var q=points.get(10);
        roles.set(16,new TypedPoint(new RouteDisplayInput.Point(q.x(),q.y()),old.type(),old.direction(),false));
        var c=EvidenceRoutePaths.classify(changed(g,points,roles));
        assertThat(c.normalization()).isEqualTo("PARTIAL_VISIBLE_SUBSEQUENCE_V1");
        assertThat(c.classifications()).doesNotContainKey(q);
    }
    @Test void partialRolesCannotGrantTheWholeRouteWitnessForLargeStopGaps(){
        var g=drift(10);
        assertThat(SourceStopOccurrenceWitness.assess(g,g.paths(),Map.of()).reasons()).contains("WHOLE_SOURCE_ROLE_ORDER_UNPROVEN");
    }
    @Test void finalGuardRejectsDeletionOrReplacementOfProtectedSegments(){
        var g=drift(10);var c=EvidenceRoutePaths.classify(g);var raw=g.paths();
        assertThat(EvidenceRoutePaths.preservesPartialSegments(g,raw,c)).isTrue();
        var points=new ArrayList<>(raw.up().points());points.remove(10);
        assertThat(EvidenceRoutePaths.preservesPartialSegments(g,new RoutePaths(new RoutePaths.Path(points),raw.down()),c)).isFalse();
        points=new ArrayList<>(raw.up().points());var q=points.get(10);points.set(10,new Point(q.x()+.000001,q.y()));
        assertThat(EvidenceRoutePaths.preservesPartialSegments(g,new RoutePaths(new RoutePaths.Path(points),raw.down()),c)).isFalse();
    }
    @Test void extraVisitRoleStillRejectsThePartialMatch(){
        var g=drift(10);var roles=new ArrayList<>(g.shape().evidence().webPoints());var old=roles.get(6);
        roles.set(6,new TypedPoint(old.point(),1,old.direction(),false));
        assertThat(EvidenceRoutePaths.classify(changed(g,g.points(),roles)).classifications()).isEmpty();
    }
    @Test void aNonStationDirectionBoundaryMustAlsoMatchExactlyWithinTheExistingTolerance(){
        var g=original();var roles=new ArrayList<>(g.shape().evidence().webPoints());
        for(int index:List.of(25,26)){var old=roles.get(index);roles.set(index,new TypedPoint(old.point(),3,old.direction(),false));}
        var t=roles.get(24);roles.set(24,new TypedPoint(t.point(),1,t.direction(),false));
        var stops=new ArrayList<>(g.busStops());stops.set(1,new Stop("B",2,"B","UP",true,g.points().get(23)));
        var complete=changed(g,g.points(),roles);
        complete=new RouteGeometry(g.routeId(),1,g.name(),g.startPoint(),g.endPoint(),2,stops,complete.shape());
        assertThat(EvidenceRoutePaths.classify(complete).classifications()).isNotEmpty();
        for(int index:List.of(24,25)) {
            var points=new ArrayList<>(complete.points());var q=points.get(index);points.set(index,new Point(q.x(),q.y()+18/111320d));
            assertThat(EvidenceRoutePaths.classify(changed(complete,points,roles)).classifications()).isEmpty();
        }
    }
    @Test void retainingOneRepeatedProtectedEdgeDoesNotPermitDeletingAnotherOccurrence(){
        var g=drift(10);var points=new ArrayList<>(g.points());var roles=new ArrayList<>(g.shape().evidence().webPoints());
        for(int i=0;i<3;i++) {
            var q=points.get(9+i);points.set(15+i,q);var old=roles.get(16+i);
            roles.set(16+i,new TypedPoint(new RouteDisplayInput.Point(q.x(),q.y()),old.type(),old.direction(),false));
        }
        var repeated=changed(g,points,roles);var c=EvidenceRoutePaths.classify(repeated);
        assertThat(c.normalization()).isEqualTo("PARTIAL_VISIBLE_SUBSEQUENCE_V1");
        var paths=repeated.paths();var up=new ArrayList<>(paths.up().points());up.subList(15,18).clear();
        assertThat(EvidenceRoutePaths.preservesPartialSegments(repeated,new RoutePaths(new RoutePaths.Path(up),paths.down()),c)).isFalse();
    }
    @Test void newGenerationKeyDoesNotReuseTheUncorrectedV7Artifact(){
        var g=drift(10);var digest=RouteDisplayGeneration.input(g).digest();var evidence=g.shape().evidence().revision();
        assertThat(RouteDisplayGeneration.key(digest,evidence,AutomaticRouteDisplay.REVISION))
                .isNotEqualTo(RouteDisplayGeneration.key(digest,evidence,"automatic-quality-v7"));
    }
}
