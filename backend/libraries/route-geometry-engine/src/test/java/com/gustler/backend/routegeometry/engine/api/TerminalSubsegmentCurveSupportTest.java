package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TerminalSubsegmentCurveSupportTest {
    private static Point p(double x,double y){return new Point(127+x/(111320*Math.cos(Math.toRadians(37))),37+y/111320);}
    private record Fixture(RouteGeometry source,RoutePaths reference,Map<Integer,Connection> connections) {}
    private static Fixture fixture(boolean repeated,boolean evidence,double firstStopX) {
        var raw=new ArrayList<>(List.of(p(-80,0),p(0,0),p(0,80)));
        if(repeated)raw.add(p(0,0));
        int turn=raw.size();raw.add(p(40,80));raw.add(p(40,-40));raw.add(p(-80,0));
        var stops=List.of(new Stop("first",1,"first","UP",true,p(firstStopX,0)),new Stop("turn",2,"turn","UP",true,raw.get(turn)),new Stop("last",3,"last","DOWN",true,p(-50,-10)));
        var typed=new ArrayList<RouteShapeEvidence.TypedPoint>();
        for(String d:List.of("UP","DOWN"))for(int i=d.equals("UP")?0:turn;i<=(d.equals("UP")?turn:raw.size()-1);i++){
            Point point=raw.get(i);typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(point.x(),point.y()),i==0||i==turn||i==raw.size()-1?1:3,d,false));
        }
        var proof=evidence?new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","a".repeat(64))),typed):RouteShapeEvidence.EMPTY;
        var source=new RouteGeometry("source",1,"source","start","end",2,stops,new Shape(new UUID(0,1),Instant.EPOCH,raw,proof));
        var upVisits=stops.stream().filter(s->s.direction().equals("UP")).toList();var downVisits=stops.stream().filter(s->s.direction().equals("DOWN")).toList();
        var sourceUp=source.paths().up().points();var sourceDown=source.paths().down().points();
        var first=RouteStopAlignment.align(sourceUp,upVisits,true).connections().get(1);var last=RouteStopAlignment.align(sourceDown,downVisits,false).connections().get(3);
        var up=new ArrayList<Point>();up.add(point(first));up.addAll(sourceUp.subList(first.segmentIndex()+1,sourceUp.size()));
        var down=new ArrayList<>(sourceDown.subList(0,last.segmentIndex()+1));down.add(point(last));
        var reference=new RoutePaths(new RoutePaths.Path(up),new RoutePaths.Path(down));var connections=new HashMap<Integer,Connection>();
        connections.putAll(RouteStopAlignment.align(up,upVisits,true).connections());connections.putAll(RouteStopAlignment.align(down,downVisits,false).connections());
        return new Fixture(source,reference,Map.copyOf(connections));
    }
    private static Point point(Connection c){return new Point(c.point().x(),c.point().y());}
    private static Optional<SourceBoundDisplayCurves.TerminalSubsegmentSupport> support(Fixture f,String direction,List<Point> reference,Map<Integer,Connection> connections){
        return SourceBoundDisplayCurves.terminalSubsegmentSupport(f.source(),direction,reference,connections,EvidenceRoutePaths.classify(f.source()).classifications());
    }

    @Test void actualDirectedSourceSubsegmentSupportsBothClippedEndsWithoutInventingPointRoles() {
        var f=fixture(false,true,-60);var before=EvidenceRoutePaths.classify(f.source()).classifications();
        var up=support(f,"UP",f.reference().up().points(),f.connections());var down=support(f,"DOWN",f.reference().down().points(),f.connections());
        assertThat(up).isPresent();assertThat(down).isPresent();
        assertThat(up.orElseThrow().sourceSegmentIndex()).isZero();assertThat(up.orElseThrow().sourceFraction()).isBetween(0d,1d);
        assertThat(before).doesNotContainKeys(f.reference().up().points().getFirst(),f.reference().down().points().getLast());
        var plan=SourceBoundDisplayCurves.build(f.source(),f.reference(),f.connections());
        assertThat(plan.curveCount()).isPositive();assertThat(plan.paths().up().points()).doesNotContain(p(0,0));
        assertThat(plan.notes()).anyMatch(s->s.startsWith("UP:TERMINAL_SUBSEGMENT_SUPPORT_CERTIFIED"));
        assertThat(plan.notes()).anyMatch(s->s.startsWith("DOWN:TERMINAL_SUBSEGMENT_SUPPORT_CERTIFIED"));
        assertThat(SourceBoundDisplayCurves.endpointsPreserved(f.reference(),plan.paths())).isTrue();
        assertThat(plan.maximumCertifiedDeviationMetres()).isLessThanOrEqualTo(2);
        assertThat(plan.continuousControlHullCertified()).isTrue();assertThat(plan.serializedChordCorrespondenceCertified()).isTrue();
        assertThat(EvidenceRoutePaths.classify(f.source()).classifications()).isEqualTo(before);
    }

    @Test void actualFloatingPointReprojectionInsideTheFirstSegmentKeepsItsDistinctLiteralConnection() {
        // WGS84 regression input: an ordinary diagonal source segment whose
        // clipped endpoint reprojects a distinct representable position inward.
        var raw=List.of(new Point(127.16286,37.41183),new Point(127.16262,37.41156),
                new Point(127.16255,37.41155),new Point(127.16255,37.4123),new Point(127.163,37.4123));
        var stops=List.of(new Stop("first",1,"first","UP",true,new Point(127.1627833,37.4118167)),
                new Stop("turn",2,"turn","UP",true,raw.get(3)),new Stop("last",3,"last","DOWN",true,raw.get(4)));
        var typed=new ArrayList<RouteShapeEvidence.TypedPoint>();
        for(String d:List.of("UP","DOWN"))for(int i=d.equals("UP")?0:3;i<=(d.equals("UP")?3:4);i++) {
            var q=raw.get(i);typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(q.x(),q.y()),i==0||i>=3?1:3,d,false));
        }
        var evidence=new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","a".repeat(64))),typed);
        var source=new RouteGeometry("diagonal-source",1,"source","A","B",2,stops,new Shape(new UUID(0,4),Instant.EPOCH,raw,evidence));
        var visits=stops.subList(0,2);var original=RouteStopAlignment.align(source.paths().up().points(),visits,true).connections().get(1);
        var up=new ArrayList<Point>();up.add(point(original));up.addAll(source.paths().up().points().subList(original.segmentIndex()+1,source.paths().up().points().size()));
        var reference=new RoutePaths(new RoutePaths.Path(up),source.paths().down());var connections=new HashMap<Integer,Connection>();
        connections.putAll(RouteStopAlignment.align(up,visits,true).connections());connections.putAll(RouteStopAlignment.align(reference.down().points(),List.of(stops.get(2)),false).connections());
        var actual=connections.get(1);assertThat(actual.segmentIndex()).isZero();assertThat(actual.segmentFraction()).isGreaterThan(0).isLessThan(1);
        assertThat(point(actual)).isNotEqualTo(up.getFirst());
        var f=new Fixture(source,reference,connections);assertThat(support(f,"UP",up,connections)).isPresent();
        var plan=SourceBoundDisplayCurves.build(source,reference,connections);
        assertThat(plan.paths().up().points()).doesNotContain(raw.get(1));
        assertThat(plan.paths().up().points().getFirst()).isEqualTo(up.getFirst());
        assertThat(connections.get(1)).isEqualTo(actual); // Neither literal is snapped or relabelled.
    }

    @Test void repeatedSourceEndpointCannotBorrowAnotherOccurrenceAsItsWitness() {
        var f=fixture(true,true,-60);
        assertThat(support(f,"UP",f.reference().up().points(),f.connections())).isEmpty();
        assertThat(SourceBoundDisplayCurves.build(f.source(),f.reference(),f.connections()).paths().up().points()).contains(p(0,0));
    }

    @Test void oppositeDirectionOrReversedReferenceCannotUseTheWitness() {
        var f=fixture(false,true,-60);var reversed=new ArrayList<>(f.reference().up().points());Collections.reverse(reversed);
        assertThat(support(f,"DOWN",f.reference().up().points(),f.connections())).isEmpty();
        assertThat(support(f,"UP",reversed,f.connections())).isEmpty();
    }

    @Test void incorrectFinalConnectionFractionOrFabricatedBoundaryFailsClosed() {
        var f=fixture(false,true,-60);var bad=new HashMap<>(f.connections());var first=bad.get(1);
        bad.put(1,new Connection(first.point(),first.segmentIndex(),.5));
        assertThat(support(f,"UP",f.reference().up().points(),bad)).isEmpty();
        var forged=new ArrayList<>(f.reference().up().points());forged.set(0,p(-59,0));
        assertThat(support(f,"UP",forged,f.connections())).isEmpty();
    }

    @Test void missingOriginalSourceEvidenceNeverCreatesSupportOrCurves() {
        var f=fixture(false,false,-60);
        assertThat(support(f,"UP",f.reference().up().points(),f.connections())).isEmpty();
        assertThat(SourceBoundDisplayCurves.build(f.source(),f.reference(),f.connections()).curveCount()).isZero();
    }

    @Test void certifiedTerminalLineageDoesNotBypassEightMetreVisitProtection() {
        var f=fixture(false,true,-4);
        assertThat(support(f,"UP",f.reference().up().points(),f.connections())).isPresent();
        var plan=SourceBoundDisplayCurves.build(f.source(),f.reference(),f.connections());
        assertThat(plan.paths().up().points()).contains(p(0,0));
        assertThat(SourceBoundDisplayCurves.endpointsPreserved(f.reference(),plan.paths())).isTrue();
    }
}
