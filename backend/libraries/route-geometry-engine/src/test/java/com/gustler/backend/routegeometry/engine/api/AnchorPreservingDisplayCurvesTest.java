package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnchorPreservingDisplayCurvesTest {
    private static Point p(double x,double y){return new Point(127+x/(111320*Math.cos(Math.toRadians(37))),37+y/111320);}
    private record Fixture(RouteGeometry geometry,SourceBoundDisplayCurves.Plan plan,Map<Integer,Connection> connections) {}
    private static Connection connection(Point p,int i,double f){return new Connection(new RouteDisplayInput.Point(p.x(),p.y()),i,f);}
    private static Fixture fixture(boolean shortEdge,boolean extraVisit,boolean shared,boolean unknownNeighbour) {
        List<Point> up=shortEdge?List.of(p(-30*Math.cos(Math.toRadians(25)),-30*Math.sin(Math.toRadians(25))),p(0,0),p(.885,0),p(.885+30*Math.cos(Math.toRadians(30)),30*Math.sin(Math.toRadians(30))),p(60,50))
                :List.of(p(-40,0),p(0,0),p(0,40),p(40,40));
        Point terminal=up.getLast();List<Point> down=shared?List.of(terminal,p(0,0),p(-40,20)):List.of(terminal,p(100,50));
        if(unknownNeighbour) {var dense=new ArrayList<Point>();Point last=down.getLast();dense.add(terminal);for(int i=1;i<=24;i++)dense.add(lerp(terminal,last,i/24.0));down=List.copyOf(dense);}
        List<Point> raw=new ArrayList<>(up);raw.addAll(down.subList(1,down.size()));
        List<Stop> stops=new ArrayList<>();Map<Integer,Connection> connections=new LinkedHashMap<>();int sequence=1;
        stops.add(new Stop("start",sequence,"start","UP",true,up.getFirst()));connections.put(sequence++,connection(up.getFirst(),0,0));
        if(extraVisit) {double f=.995;Point a=up.get(0),b=up.get(1);Point at=new Point(a.x()+f*(b.x()-a.x()),a.y()+f*(b.y()-a.y()));
            stops.add(new Stop("near-anchor",sequence,"near-anchor","UP",true,p(-.2,-20)));connections.put(sequence++,connection(at,0,f));}
        stops.add(new Stop("anchor",sequence,"anchor","UP",true,p(15,-20)));connections.put(sequence++,connection(up.get(1),1,0));
        int turnSequence=sequence;stops.add(new Stop("turn",sequence,"turn","UP",true,terminal));connections.put(sequence++,connection(terminal,up.size()-2,1));
        stops.add(new Stop("last",sequence,"last","DOWN",true,down.getLast()));connections.put(sequence,connection(down.getLast(),down.size()-2,1));
        var typed=new ArrayList<RouteShapeEvidence.TypedPoint>();
        for(String direction:List.of("UP","DOWN")) {
            List<Point> points=direction.equals("UP")?up:down;
            for(int i=0;i<points.size();i++) {Point at=points.get(i);int type=i==0||i==points.size()-1||direction.equals("UP")&&i==1?1:3;
                if(unknownNeighbour&&direction.equals("UP")&&i==0)at=new Point(at.x()+.001,at.y());
                typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(at.x(),at.y()),type,direction,true));}
        }
        var evidence=new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","a".repeat(64))),typed);
        var geometry=new RouteGeometry("synthetic-anchor",1,"route","A","B",turnSequence,stops,new Shape(new UUID(0,3),Instant.EPOCH,raw,evidence));
        var reference=new RoutePaths(new RoutePaths.Path(up),new RoutePaths.Path(down));
        return new Fixture(geometry,SourceBoundDisplayCurves.build(geometry,reference,connections),connections);
    }
    @Test void exactTypeOneAndSourceStopAnchorGainContinuousTangencyWithoutMovingGps() {
        var f=fixture(false,false,false,false);var original=f.geometry().busStops();var result=AnchorPreservingDisplayCurves.build(f.geometry(),f.plan(),f.connections());
        assertThat(result.anchoredCornerCount()).isPositive();assertThat(result.plan().curveCount()).isEqualTo(f.plan().curveCount()+result.anchoredCornerCount());
        assertThat(result.plan().paths().up().points()).contains(p(0,0));assertThat(f.geometry().busStops()).isEqualTo(original);
        assertThat(result.proofs()).allSatisfy(proof->{assertThat(proof.commonTangentCertified()).isTrue();assertThat(proof.strictlyForwardDerivativeHull()).isTrue();assertThat(proof.maximumCertifiedDeviationMetres()).isLessThanOrEqualTo(2);});
        var points=result.plan().paths().up().points();int i=points.indexOf(p(0,0));assertThat(vertexAngle(points,i)).isLessThan(8);
        assertThat(SourceBoundDisplayCurves.endpointsPreserved(f.plan().paths(),result.plan().paths())).isTrue();assertThat(result.plan().maximumCertifiedDeviationMetres()).isLessThanOrEqualTo(2);
    }
    @Test void interiorStopIsAnExactKnotAndShortSupportDoesNotUseOldFilletMinimum() {
        var f=fixture(false,true,false,false);var result=AnchorPreservingDisplayCurves.build(f.geometry(),f.plan(),f.connections());assertThat(result.anchoredCornerCount()).isPositive();
        var connection=f.connections().get(2);var point=new Point(connection.point().x(),connection.point().y());assertThat(result.plan().paths().up().points()).contains(point,p(0,0));
        assertThat(result.proofs()).anySatisfy(proof->assertThat(proof.sourceProgress()-proof.supportStart()).isLessThan(.5));
        assertThat(vertexAngle(result.plan().paths().up().points(),result.plan().paths().up().points().indexOf(point))).isLessThan(8);
    }
    @Test void consecutiveSubMetreTypeOneAndTypeThreeCornersKeepBothLiteralPoints() {
        var f=fixture(true,false,false,false);var result=AnchorPreservingDisplayCurves.build(f.geometry(),f.plan(),f.connections());
        assertThat(result.anchoredCornerCount()).isGreaterThanOrEqualTo(2);assertThat(result.plan().paths().up().points()).contains(p(0,0),p(.885,0));
        assertThat(vertexAngle(result.plan().paths().up().points(),result.plan().paths().up().points().indexOf(p(0,0)))).isLessThan(8);
        assertThat(vertexAngle(result.plan().paths().up().points(),result.plan().paths().up().points().indexOf(p(.885,0)))).isLessThan(8);
    }
    @Test void emittedChordsAreBoundAtTheSameMonotoneSourcePosition() {
        var f=fixture(false,true,false,false);var result=AnchorPreservingDisplayCurves.build(f.geometry(),f.plan(),f.connections());assertThat(result.anchoredCornerCount()).isPositive();
        for(var map:result.plan().correspondences().values()) {var frame=map.metricFrame();double[] chain=new double[map.referencePoints().size()];
            for(int i=1;i<chain.length;i++)chain[i]=chain[i-1]+distance(map.referencePoints().get(i-1),map.referencePoints().get(i),frame);
            for(int i=1;i<map.displayPoints().size();i++) {double lo=map.referenceProgressAtDisplayVertices().get(i-1),hi=map.referenceProgressAtDisplayVertices().get(i);assertThat(hi).isGreaterThan(lo);
                for(double t:new double[]{0,.25,.5,.75,1}) {double s=lo+(hi-lo)*t;int j=0;while(j+1<chain.length-1&&chain[j+1]<s)j++;double fraction=(s-chain[j])/(chain[j+1]-chain[j]);
                    Point a=map.referencePoints().get(j),b=map.referencePoints().get(j+1);Point expected=lerp(a,b,fraction);Point actual=lerp(map.displayPoints().get(i-1),map.displayPoints().get(i),t);
                    assertThat(distance(actual,expected,frame)).isLessThanOrEqualTo(2);}}
        }
    }
    @Test void repeatedSharedOccurrenceIsNotRoundedThroughAsAnOrdinaryAnchor() {
        var f=fixture(false,false,true,false);var result=AnchorPreservingDisplayCurves.build(f.geometry(),f.plan(),f.connections());
        assertThat(result.proofs()).noneSatisfy(proof->{var map=f.plan().correspondences().get(proof.direction());assertThat(pointAt(map,proof.sourceProgress())).isEqualTo(p(0,0));});
        assertThat(result.plan().paths().up().points()).contains(p(0,0));assertThat(result.plan().paths().down().points()).contains(p(0,0));
    }
    @Test void anUnmatchedSupportNeighbourCannotBecomeNewSourceGeometry() {
        var f=fixture(false,false,false,true);var classified=EvidenceRoutePaths.classify(f.geometry()).classifications();
        assertThat(classified).containsKey(p(0,0));assertThat(classified).doesNotContainKey(p(-40,0));
        var result=AnchorPreservingDisplayCurves.build(f.geometry(),f.plan(),f.connections());
        assertThat(result.proofs()).noneSatisfy(proof->assertThat(pointAt(f.plan().correspondences().get(proof.direction()),proof.sourceProgress())).isEqualTo(p(0,0)));
    }
    @Test void corruptedBaselineConnectionCannotBeSnappedIntoAValidAnchor() {
        var f=fixture(false,false,false,false);var altered=new HashMap<>(f.connections());altered.put(2,connection(p(1,1),1,0));
        var result=AnchorPreservingDisplayCurves.build(f.geometry(),f.plan(),altered);assertThat(result.plan()).isSameAs(f.plan());assertThat(result.anchoredCornerCount()).isZero();
    }
    @Test void tinyDistinctVisitNearTheFirstPointRemainsAnOrderedLiteralKnot() {
        var f=fixture(false,true,false,false);var old=f.connections().get(2);Point a=f.plan().correspondences().get("UP").referencePoints().get(0),b=f.plan().correspondences().get("UP").referencePoints().get(1);
        double fraction=1e-10;Point tiny=lerp(a,b,fraction);assertThat(tiny).isNotEqualTo(a);
        var stops=new ArrayList<>(f.geometry().busStops());var before=stops.get(1);stops.set(1,new Stop(before.stationId(),before.stopSequence(),before.name(),before.direction(),before.boardingAllowed(),tiny));
        var geometry=new RouteGeometry(f.geometry().routeId(),1,"route","A","B",f.geometry().turnSequence(),stops,f.geometry().shape());
        var connections=new LinkedHashMap<>(f.connections());connections.put(2,connection(tiny,0,fraction));
        var current=SourceBoundDisplayCurves.build(geometry,new RoutePaths(new RoutePaths.Path(f.plan().correspondences().get("UP").referencePoints()),new RoutePaths.Path(f.plan().correspondences().get("DOWN").referencePoints())),connections);
        var result=AnchorPreservingDisplayCurves.build(geometry,current,connections);assertThat(result.anchoredCornerCount()).isPositive();
        var mapping=result.plan().correspondences().get("UP");int at=mapping.displayPoints().indexOf(tiny);assertThat(at).isPositive();assertThat(mapping.displayPoints().getFirst()).isEqualTo(a);
        assertThat(mapping.referenceProgressAtDisplayVertices().get(at)).isGreaterThan(0).isLessThan(1e-6);
    }
    @Test void splittingLongReferenceRendererChordAtLiteralVisitMustNotEvadeTheTwoMetreBound() {
        var original=fixture(false,true,false,false);Point first=p(-100000,-100000);var raw=new ArrayList<>(original.geometry().points());Point previous=raw.set(0,first);
        var typed=new ArrayList<RouteShapeEvidence.TypedPoint>();
        for(var t:original.geometry().shape().evidence().webPoints()) {Point q=t.point().x()==previous.x()&&t.point().y()==previous.y()?first:new Point(t.point().x(),t.point().y());typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(q.x(),q.y()),t.type(),t.direction(),t.nodeConfirmed()));}
        var evidence=new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","a".repeat(64))),typed);
        Point middle=lerp(first,p(0,0),.5);var stops=new ArrayList<>(original.geometry().busStops());stops.set(0,new Stop("start",1,"start","UP",true,first));stops.set(1,new Stop("near-anchor",2,"near-anchor","UP",true,middle));
        var geometry=new RouteGeometry("long-reference",1,"route","A","B",4,stops,new Shape(new UUID(0,7),Instant.EPOCH,raw,evidence));
        var paths=new RoutePaths(new RoutePaths.Path(raw.subList(0,4)),new RoutePaths.Path(raw.subList(3,raw.size())));var connections=new LinkedHashMap<>(original.connections());connections.put(1,connection(first,0,0));connections.put(2,connection(middle,0,.5));
        var prior=SourceBoundDisplayCurves.build(geometry,paths,connections);var result=AnchorPreservingDisplayCurves.build(geometry,prior,connections);
        assertThat(result.plan()).isSameAs(prior);assertThat(result.notes()).anyMatch(n->n.contains("FINAL_SERIALIZED_SOURCE_LEASH_EXCEEDED"));
    }
    private static Point pointAt(SourceBoundDisplayCurves.Correspondence map,double s){int i=Collections.binarySearch(map.referenceProgressAtDisplayVertices(),s);return map.displayPoints().get(i);}
    private static Point lerp(Point a,Point b,double f){return new Point(a.x()+f*(b.x()-a.x()),a.y()+f*(b.y()-a.y()));}
    private static double distance(Point a,Point b,SourceBoundDisplayCurves.Frame f){return Math.hypot((a.x()-b.x())*f.xMetresPerDegree(),(a.y()-b.y())*f.yMetresPerDegree());}
    private static double vertexAngle(List<Point> points,int i){Point a=points.get(i-1),b=points.get(i),c=points.get(i+1);double scale=Math.cos(Math.toRadians(b.y()));double x=(b.x()-a.x())*scale,y=b.y()-a.y(),u=(c.x()-b.x())*scale,v=c.y()-b.y();return Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(x*u+y*v)/(Math.hypot(x,y)*Math.hypot(u,v))))));}
}
