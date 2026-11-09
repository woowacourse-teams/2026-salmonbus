package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.assertThat;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceStopOccurrenceWitnessTest {
    private static Point p(double x,double y){return new Point(127+x/(111320*Math.cos(Math.toRadians(37))),37+y/111320);}
    private RouteGeometry geometry(double interiorGap,double turnGap,boolean shared) {
        var points=List.of(p(0,0),p(50,0),p(100,0),p(150,0),p(200,0),p(200,0),p(200,100),p(150,100),p(100,100),p(50,100),p(0,100));
        var typed=new ArrayList<RouteShapeEvidence.TypedPoint>();
        for(int i=0;i<points.size();i++){var point=points.get(i);typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(point.x(),point.y()),Set.of(1,3,4,7,10).contains(i)||shared&&i==5?1:3,i<=4?"UP":"DOWN",false));}
        var evidence=new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","b".repeat(64))),typed);
        return new RouteGeometry("fixture",1,"fixture","A","B",3,List.of(
                new Stop("repeat",1,"first","UP",true,p(50,interiorGap)),new Stop("B",2,"second","UP",true,p(150,0)),
                new Stop("turn",3,"turn","UP",true,p(200,-turnGap)),new Stop("C",4,"fourth","DOWN",true,p(150,100)),
                new Stop("repeat",5,"last","DOWN",true,p(0,100))),
                new Shape(UUID.randomUUID(),Instant.EPOCH,points,evidence,"a".repeat(64)));
    }
    private Map<Integer,Connection> align(RouteGeometry g,RoutePaths paths){var result=new HashMap<Integer,Connection>();for(String d:List.of("UP","DOWN")){var alignment=RouteStopAlignment.align(d.equals("UP")?paths.up().points():paths.down().points(),g.busStops().stream().filter(s->s.direction().equals(d)).toList(),d.equals("UP"));assertThat(alignment.usable()).isTrue();result.putAll(alignment.connections());}return result;}
    private RouteGeometry proof(RouteGeometry g,List<RouteShapeEvidence.TypedPoint> typed,String manifest,List<RouteShapeEvidence.Source> sources){return new RouteGeometry(g.routeId(),g.referenceVersionId(),g.name(),g.startPoint(),g.endPoint(),g.turnSequence(),g.busStops(),new Shape(g.shape().id(),g.shape().receivedAt(),g.points(),new RouteShapeEvidence("GBIS_API",sources,typed),manifest));}
    private Connection at(List<Point> path,int segment,double fraction){var p=RouteStopAlignment.interpolate(path.get(segment),path.get(segment+1),fraction);return new Connection(new RouteDisplayInput.Point(p.x(),p.y()),segment,fraction);}

    @Test void largeGapWithWholeOrderedRolesIsReviewAndPreservesRawGpsAndWarnings(){var g=geometry(35,0,true);var result=AutomaticRouteDisplay.build(g,"test");assertThat(result.quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.REVIEW_REQUIRED);assertThat(result.connections()).hasSize(5);assertThat(result.quality().issues()).anyMatch(i->i.code().equals("STOP_CONNECTION_GAP")&&i.sequence()==1);assertThat(g.busStops().getFirst().point()).isEqualTo(p(50,35));assertThat(result.stationPositions()).isEmpty();}
    @Test void identicalGeometryWithoutRolesStaysWithheld(){var g=geometry(35,0,true);g=proof(g,List.of(),g.shape().sourceManifestId(),g.shape().evidence().sources());assertThat(AutomaticRouteDisplay.build(g,"test").quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.WITHHELD);}
    @Test void missingProvenanceOrExpiredRolesCannotSupportLargeGap(){var g=geometry(35,0,true);assertThat(AutomaticRouteDisplay.build(proof(g,g.shape().evidence().webPoints(),null,g.shape().evidence().sources()),"test").quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.WITHHELD);var expired=List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","b".repeat(64),Instant.EPOCH.minusSeconds(1)));assertThat(SourceStopOccurrenceWitness.assess(proof(g,g.shape().evidence().webPoints(),g.shape().sourceManifestId(),expired),g.paths(),align(g,g.paths())).visits()).isEmpty();}
    @Test void mismatchedRoleCountAndDirectionCannotBorrowOrdinal(){var g=geometry(35,0,true);var typed=new ArrayList<>(g.shape().evidence().webPoints());var t=typed.get(1);typed.set(1,new RouteShapeEvidence.TypedPoint(t.point(),3,t.direction(),false));assertThat(SourceStopOccurrenceWitness.assess(proof(g,typed,g.shape().sourceManifestId(),g.shape().evidence().sources()),g.paths(),align(g,g.paths())).visits()).isEmpty();typed=new ArrayList<>(g.shape().evidence().webPoints());typed.set(1,new RouteShapeEvidence.TypedPoint(t.point(),1,"DOWN",false));assertThat(SourceStopOccurrenceWitness.assess(proof(g,typed,g.shape().sourceManifestId(),g.shape().evidence().sources()),g.paths(),align(g,g.paths())).visits()).isEmpty();}
    @Test void explicitSharedTurnSupportsRepresentativeRenderingButNoPlatformClaim(){var g=geometry(0,90,true);var result=AutomaticRouteDisplay.build(g,"test");assertThat(result.quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.REVIEW_REQUIRED);var witness=SourceStopOccurrenceWitness.assess(g,g.paths(),align(g,g.paths()));assertThat(witness.visits().get(3).kind()).isEqualTo("SHARED_TURN_EVENT");assertThat(result.quality().issues()).anyMatch(i->i.code().equals("STOP_CONNECTION_GAP")&&i.sequence()==3);assertThat(result.stationPositions()).isEmpty();}
    @Test void sameTerminalWithoutExplicitSharedRoleRemainsWithheld(){var g=geometry(0,90,false);assertThat(AutomaticRouteDisplay.build(g,"test").quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.WITHHELD);}
    @Test void hundredMetreHardAssociationCapRemains(){var g=geometry(-101,0,true);assertThat(AutomaticRouteDisplay.build(g,"test").quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.WITHHELD);assertThat(RouteStopAlignment.MAX_OFFSET).isEqualTo(100);}
    @Test void foreignVisitWindowOrFabricatedConnectionIsRejected(){var g=geometry(35,0,true);var paths=g.paths();var connections=align(g,paths);connections.put(1,at(paths.up().points(),3,.5));assertThat(SourceStopOccurrenceWitness.assess(g,paths,connections).visits()).isEmpty();connections=align(g,paths);var old=connections.get(1);connections.put(1,new Connection(new RouteDisplayInput.Point(old.point().x()+.00001,old.point().y()),old.segmentIndex(),old.segmentFraction()));assertThat(SourceStopOccurrenceWitness.assess(g,paths,connections).visits()).isEmpty();}
    @Test void repeatedStationIdsRetainDistinctOccurrences(){var g=geometry(35,0,true);var assessment=SourceStopOccurrenceWitness.assess(g,g.paths(),align(g,g.paths()));assertThat(assessment.reasons()).isEmpty();assertThat(assessment.visits().get(1).rawIndex()).isEqualTo(1);assertThat(g.busStops().getFirst().stationId()).isEqualTo(g.busStops().getLast().stationId());assertThat(assessment.visits().get(1).direction()).isEqualTo("UP");}
    @Test void curveSourceWindowBoundaryCannotBorrowTheTwoCentimetreOccurrenceTolerance(){var g=geometry(35,0,true);var paths=g.paths();var before=align(g,paths);before.put(2,at(paths.up().points(),2,.00002));var plan=SourceBoundDisplayCurves.build(g,paths,before);var after=RouteStopIntervals.preserveCurveConnections(g.busStops(),paths,before,plan).connections();assertThat(SourceStopOccurrenceWitness.throughCurve(g,paths,before,plan,after).supports(g.busStops().get(1))).isTrue();var changed=new HashMap<>(after);var m=plan.correspondences().get("UP");assertThat(m.displayPoints()).isEqualTo(paths.up().points());double boundary=SourceStopOccurrenceWitness.assess(g,paths,before).visits().get(2).lowerSourceAxis();changed.put(2,at(m.displayPoints(),(int)boundary,boundary-(int)boundary));assertThat(RouteStopIntervals.preservedCurveOccurrences(g.busStops(),paths,before,plan.paths(),changed,plan).usable()).isTrue();assertThat(SourceStopOccurrenceWitness.throughCurve(g,paths,before,plan,changed).visits()).isEmpty();}
    @Test void sameXyOnLaterLapDoesNotIdentifyTheFirstVisitOrItsCurveOccurrence(){
        var original=geometry(35,0,true);var points=new ArrayList<>(original.points());points.add(3,p(50,0));
        var typed=new ArrayList<>(original.shape().evidence().webPoints());typed.add(3,new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(p(50,0).x(),p(50,0).y()),3,"UP",false));
        var g=new RouteGeometry(original.routeId(),1,original.name(),"A","B",3,original.busStops(),new Shape(UUID.randomUUID(),Instant.EPOCH,points,new RouteShapeEvidence("GBIS_API",original.shape().evidence().sources(),typed),"a".repeat(64)));
        var paths=g.paths();assertThat(paths).isNotNull();var connections=new HashMap<Integer,Connection>();
        connections.put(1,at(paths.up().points(),1,0));connections.put(2,at(paths.up().points(),4,0));connections.put(3,at(paths.up().points(),paths.up().points().size()-2,1));
        for(String d:List.of("DOWN")){var a=RouteStopAlignment.align(paths.down().points(),g.busStops().stream().filter(t->t.direction().equals(d)).toList(),false);assertThat(a.usable()).isTrue();connections.putAll(a.connections());}
        var base=SourceStopOccurrenceWitness.assess(g,paths,connections);assertThat(base.supports(g.busStops().getFirst())).isTrue();
        var changed=new HashMap<>(connections);changed.put(1,at(paths.up().points(),3,0));
        assertThat(changed.get(1).point()).isEqualTo(connections.get(1).point());
        assertThat(SourceStopOccurrenceWitness.assess(g,paths,changed).visits()).isEmpty();
        var plan=SourceBoundDisplayCurves.build(g,paths,connections);var after=RouteStopIntervals.preserveCurveConnections(g.busStops(),paths,connections,plan).connections();var wrong=new HashMap<>(after);
        var mapping=plan.correspondences().get("UP");int count=0,index=-1;for(int i=0;i<mapping.displayPoints().size();i++)if(mapping.displayPoints().get(i).equals(p(50,0)) && ++count==2){index=i;break;}
        assertThat(index).isGreaterThan(0);wrong.put(1,at(mapping.displayPoints(),index,0));
        assertThat(SourceStopOccurrenceWitness.throughCurve(g,paths,connections,plan,wrong).visits()).isEmpty();
    }
    @Test void evenFutureFiniteRoleExpiryIsExcludedUntilCacheLifecycleSupportsIt(){var g=geometry(35,0,true);var future=List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","b".repeat(64),Instant.EPOCH.plusSeconds(3600)));assertThat(SourceStopOccurrenceWitness.assess(proof(g,g.shape().evidence().webPoints(),g.shape().sourceManifestId(),future),g.paths(),align(g,g.paths())).visits()).isEmpty();}

    @Test void finiteRoleDeadlineNeverOutlivesCachedWitnessAndDoesNotChangeSmallGapPolicy(){
        for(var deadline:List.of(Instant.EPOCH.minusSeconds(1),Instant.EPOCH,Instant.EPOCH.plusSeconds(3600))){
            var sources=List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","b".repeat(64),deadline));
            var high=geometry(35,0,true);high=proof(high,high.shape().evidence().webPoints(),high.shape().sourceManifestId(),sources);
            assertThat(AutomaticRouteDisplay.build(high,"deadline").quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.WITHHELD);
            var small=geometry(0,0,true);small=proof(small,small.shape().evidence().webPoints(),small.shape().sourceManifestId(),sources);
            assertThat(AutomaticRouteDisplay.build(small,"deadline").paths()).isNotNull();
        }
    }
    @Test void zeroLengthAliasesAtOuterBoundaryRetainTheOuterSourceAxis()throws Exception {
        var method=SourceStopOccurrenceWitness.class.getDeclaredMethod("axis",double[].class,double.class);method.setAccessible(true);
        assertThat((double)method.invoke(null,new double[]{0,50,100,150,200,200},200d)).isEqualTo(5);
        assertThat((double)method.invoke(null,new double[]{0,0,50,100},0d)).isZero();
    }

    private RouteGeometry longTurn(double turnX,Point nextGps){
        var points=List.of(p(50,0),p(100,0),p(150,0),p(200,0),p(1000,0),p(1000,0),p(1000,100),p(900,100),p(500,100),p(100,100),p(0,100));
        var roles=new ArrayList<RouteShapeEvidence.TypedPoint>();for(int i=0;i<points.size();i++){var q=points.get(i);roles.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(q.x(),q.y()),Set.of(1,3,4,5,7,10).contains(i)?1:3,i<=4?"UP":"DOWN",false));}
        return new RouteGeometry("long-turn",1,"long-turn","A","B",3,List.of(new Stop("A",1,"A","UP",true,p(100,0)),new Stop("B",2,"B","UP",true,p(200,0)),new Stop("T",3,"T","UP",true,p(turnX,-90)),new Stop("C",4,"C","DOWN",true,nextGps),new Stop("D",5,"D","DOWN",true,p(0,100))),new Shape(UUID.randomUUID(),Instant.EPOCH,points,new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","b".repeat(64))),roles),"a".repeat(64)));
    }
    @Test void longIncidentEdgeCannotMoveSharedTurnBeforeItsOriginalRawWindow(){
        var g=longTurn(500,p(900,100));assertThat(g.paths()).isNotNull();var connections=align(g,g.paths());
        assertThat(SourceStopOccurrenceWitness.assess(g,g.paths(),connections).reasons()).contains("CONNECTION_OUTSIDE_RAW_TYPE1_WINDOW");
        assertThat(AutomaticRouteDisplay.build(g,"raw-window").quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.WITHHELD);
        var plan=SourceBoundDisplayCurves.build(g,g.paths(),connections);var after=RouteStopIntervals.preserveCurveConnections(g.busStops(),g.paths(),connections,plan).connections();
        assertThat(SourceStopOccurrenceWitness.throughCurve(g,g.paths(),connections,plan,after).visits()).isEmpty();
    }
    @Test void sharedTurnInsideOriginalRawWindowRetainsRendering(){var g=longTurn(700,p(900,100));assertThat(SourceStopOccurrenceWitness.assess(g,g.paths(),align(g,g.paths())).supports(g.busStops().get(2))).isTrue();assertThat(AutomaticRouteDisplay.build(g,"raw-window").quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.REVIEW_REQUIRED);}
    @Test void movingSeamMustNotExpandTheAdjacentDownVisitRawWindow(){var g=longTurn(700,p(975,-35));assertThat(SourceStopOccurrenceWitness.assess(g,g.paths(),align(g,g.paths())).reasons()).contains("CONNECTION_OUTSIDE_RAW_TYPE1_WINDOW");assertThat(AutomaticRouteDisplay.build(g,"raw-window").quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.WITHHELD);}
    @Test void interiorTypeTwoDeletionCannotExpandOwnOriginalRawWindow(){
        var base=geometry(35,0,true);var points=List.of(p(0,0),p(50,0),p(60,0),p(70,20),p(80,0),p(150,0),p(200,0),p(200,0),p(200,100),p(150,100),p(0,100));
        var roles=new ArrayList<RouteShapeEvidence.TypedPoint>();for(int i=0;i<points.size();i++){var q=points.get(i);roles.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(q.x(),q.y()),Set.of(1,5,6,7,9,10).contains(i)?1:i==3?2:3,i<=6?"UP":"DOWN",true));}
        var visits=new ArrayList<>(base.busStops());visits.set(0,new Stop("repeat",1,"first","UP",true,p(95,35)));
        var g=new RouteGeometry(base.routeId(),1,"detour","A","B",3,visits,new Shape(UUID.randomUUID(),Instant.EPOCH,points,new RouteShapeEvidence("GBIS_API",base.shape().evidence().sources(),roles),"a".repeat(64)));
        var original=g.paths();var up=new ArrayList<>(original.up().points());up.remove(p(70,20));var candidate=new RoutePaths(new RoutePaths.Path(up),original.down());
        var connections=align(g,candidate);assertThat(connections.get(1).point().x()).isCloseTo(p(95,0).x(),org.assertj.core.data.Offset.offset(1e-12));
        assertThat(SourceStopOccurrenceWitness.assess(g,candidate,connections).reasons()).contains("CONNECTION_OUTSIDE_RAW_TYPE1_WINDOW");
        var plan=SourceBoundDisplayCurves.build(g,candidate,connections);var after=RouteStopIntervals.preserveCurveConnections(g.busStops(),candidate,connections,plan).connections();assertThat(SourceStopOccurrenceWitness.throughCurve(g,candidate,connections,plan,after).visits()).isEmpty();
    }

    private RouteGeometry longOutgoingTurn(double y){
        var base=longTurn(700,p(900,100));var points=new ArrayList<>(base.points());for(int i=6;i<points.size();i++)points.set(i,p(new double[]{1000,900,500,100,0}[i-6],1000));
        var roles=new ArrayList<>(base.shape().evidence().webPoints());for(int i=0;i<points.size();i++){var old=roles.get(i);var q=points.get(i);roles.set(i,new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(q.x(),q.y()),old.type(),old.direction(),false));}
        var visits=new ArrayList<>(base.busStops());visits.set(2,new Stop("T",3,"T","UP",true,p(1090,y)));visits.set(3,new Stop("C",4,"C","DOWN",true,p(900,1000)));visits.set(4,new Stop("D",5,"D","DOWN",true,p(0,1000)));
        return new RouteGeometry(base.routeId(),1,base.name(),"A","B",3,visits,new Shape(UUID.randomUUID(),Instant.EPOCH,points,new RouteShapeEvidence("GBIS_API",base.shape().evidence().sources(),roles),"a".repeat(64)));
    }
    @Test void outgoingSharedTurnCannotPassItsGlobalNextVisitMidpoint(){var g=longOutgoingTurn(700);assertThat(SourceStopOccurrenceWitness.assess(g,g.paths(),align(g,g.paths())).reasons()).contains("CONNECTION_OUTSIDE_RAW_TYPE1_WINDOW");assertThat(AutomaticRouteDisplay.build(g,"outgoing").quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.WITHHELD);}
    @Test void outgoingSharedTurnInsideBothGlobalMidpointsStillRenders(){var g=longOutgoingTurn(400);assertThat(SourceStopOccurrenceWitness.assess(g,g.paths(),align(g,g.paths())).supports(g.busStops().get(2))).isTrue();assertThat(AutomaticRouteDisplay.build(g,"outgoing").quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.REVIEW_REQUIRED);}

}
