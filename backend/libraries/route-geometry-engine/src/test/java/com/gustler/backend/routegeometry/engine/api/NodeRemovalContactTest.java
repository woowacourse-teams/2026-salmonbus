package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.assertThat;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class NodeRemovalContactTest {
    private static Point p(double x,double y) {return new Point(127+x*.00001,37+y*.00001);}
    private static RoutePaths paths(List<Point> edited,List<Point> other,String direction) {
        return direction.equals("UP") ? new RoutePaths(new RoutePaths.Path(edited),new RoutePaths.Path(other))
                :new RoutePaths(new RoutePaths.Path(other),new RoutePaths.Path(edited));
    }
    private static boolean allows(List<Point> edited,int index,List<Point> other,String direction) {
        var original=paths(edited,other,direction);
        return RouteDisplayTopology.safeShortcutContacts(edited,index,other,direction,
                RouteDisplayTopology.contacts(original),RouteDisplayTopology.rendererContacts(original));
    }
    private static RouteGeometry reviewCase(boolean extraSafeNode) {
        var points=new ArrayList<>(List.of(p(0,0),p(50,10),p(100,0),p(150,-20),p(50,0),p(0,-10)));
        if(extraSafeNode)points.addAll(0,List.of(p(-100,-10),p(-50,-5)));
        int shift=extraSafeNode?2:0;
        var stops=List.of(new Stop("A",1,"A","UP",true,points.getFirst()),
                new Stop("B",2,"B","UP",true,points.get(shift+2)),
                new Stop("D",3,"D","DOWN",true,points.get(shift+4)),
                new Stop("E",4,"E","DOWN",true,points.getLast()));
        var typed=new ArrayList<RouteShapeEvidence.TypedPoint>();
        for(int i=0;i<points.size();i++) {
            var point=points.get(i);
            int type=i==shift+1 || extraSafeNode && i==1?2:stops.stream().anyMatch(s->s.point().equals(point))?1:3;
            typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(point.x(),point.y()),type,i<=shift+2?"UP":"DOWN",true));
        }
        return new RouteGeometry("204000057",1,"review","A","B",2,stops,
                new Shape(new UUID(0,1),Instant.EPOCH,points,new RouteShapeEvidence("GBIS_API",
                        List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE",null)),typed)));
    }
    @Test void reviewTouchCannotBecomeAPassedBaseline() {
        var g=reviewCase(false);var floor=EvidenceRoutePaths.buildSequentialFloor(g);
        assertThat(floor.up().points()).contains(p(50,10));
        var display=AutomaticRouteDisplay.build(g,RouteDisplayGeneration.input(g).digest());
        assertThat(display.paths()).isNotNull();
        assertThat(RouteDisplayTopology.noNewContacts(g.paths(),floor)).isTrue();
        assertThat(RouteDisplayTopology.noNewContacts(g.paths(),display.paths())).isTrue();
        assertThat(display.connections()).hasSize(4);
    }
    @Test void rejectedRemovalDoesNotUndoAnIndependentSafeRemoval() {
        var g=reviewCase(true);var floor=EvidenceRoutePaths.buildSequentialFloor(g);
        assertThat(floor.up().points()).contains(p(50,10)).doesNotContain(p(-50,-5));
        assertThat(RouteDisplayTopology.noNewContacts(g.paths(),floor)).isTrue();
    }
    @Test void newEndpointTouchAndOverlapAreRejectedInEitherDirection() {
        var edited=List.of(p(0,0),p(50,10),p(100,0));
        for(String direction:List.of("UP","DOWN")) {
            assertThat(allows(edited,1,List.of(p(50,0),p(50,-20)),direction)).isFalse();
            assertThat(allows(edited,1,List.of(p(30,0),p(70,0)),direction)).isFalse();
        }
    }
    @Test void anExistingTurnContactRemainsAllowed() {
        var edited=List.of(p(0,0),p(50,10),p(100,0));
        assertThat(allows(edited,1,List.of(p(100,0),p(150,-20)),"UP")).isTrue();
    }
    @Test void duplicatePointsDoNotTurnAdjacentEdgesIntoFalseContacts() {
        var edited=List.of(p(-10,0),p(0,0),p(0,0),p(50,10),p(100,0),p(100,0),p(110,0));
        assertThat(allows(edited,3,List.of(p(0,-20),p(100,-20)),"UP")).isTrue();
        var folded=List.of(p(60,0),p(0,0),p(0,0),p(50,10),p(100,0));
        assertThat(allows(folded,3,List.of(p(0,-20),p(100,-20)),"UP")).isFalse();
    }
    @Test void microscopicSeparationIsNotAnActualTouch() {
        var edited=List.of(p(0,0),p(50,10),p(100,0));
        assertThat(allows(edited,1,List.of(p(50,-.00001),p(50,-20)),"UP")).isTrue();
        assertThat(allows(edited,1,List.of(p(50,.00001),p(50,-20)),"UP")).isFalse();
    }
    @Test void incompleteEvidenceAndUnsupportedRendererCoordinatesFailClosed() {
        var empty=new RouteDisplayTopology.Contacts(List.of(),true);
        var incomplete=new RouteDisplayTopology.Contacts(List.of(),false);
        assertThat(RouteDisplayTopology.safeShortcutContacts(List.of(p(0,0),p(50,10),p(100,0)),1,
                List.of(p(0,-20),p(100,-20)),"UP",incomplete,empty)).isFalse();
        assertThat(RouteDisplayTopology.safeShortcutContacts(List.of(new Point(127,86),new Point(127.001,86.001),new Point(127.002,86)),1,
                List.of(p(0,-20),p(100,-20)),"UP",empty,empty)).isFalse();
    }
    @Test void workBudgetExhaustionDoesNotApproveDeletion() {
        var empty=new RouteDisplayTopology.Contacts(List.of(),true);
        assertThat(RouteDisplayTopology.safeShortcutContacts(List.of(p(0,0),p(50,10),p(100,0)),1,
                Collections.nCopies(2_000_002,p(1000,1000)),"UP",empty,empty)).isFalse();
    }
    @Test void incrementalGuardMatchesWholeBundleCheckAcrossSeededCases() {
        var random=new Random(338246);int allowed=0,blocked=0;
        for(int trial=0;trial<500;trial++) {
            var edited=new ArrayList<Point>();var other=new ArrayList<Point>();
            for(int i=0;i<7;i++)edited.add(p(random.nextInt(12),random.nextInt(12)));
            for(int i=0;i<5;i++)other.add(p(random.nextInt(12),random.nextInt(12)));
            int target=1+random.nextInt(5);if(edited.get(target-1).equals(edited.get(target+1)))continue;
            String direction=trial%2==0?"UP":"DOWN";
            var original=paths(edited,other,direction);var candidate=new ArrayList<>(edited);candidate.remove(target);
            boolean expected=RouteDisplayTopology.noNewContacts(original,paths(candidate,other,direction));
            boolean actual=allows(edited,target,other,direction);
            assertThat(actual).as("trial %s direction %s",trial,direction).isEqualTo(expected);
            if(actual)allowed++;else blocked++;
        }
        assertThat(allowed).isGreaterThan(20);assertThat(blocked).isGreaterThan(20);
    }
    @Test void sharedRoleOccurrencesCanStillBeRemovedAsASafeWholeBundle() {
        var points=List.of(p(-100,0),p(0,10),p(100,0),p(100,-10),p(0,10),p(-100,-10));
        var stops=List.of(new Stop("A",1,"A","UP",true,points.get(0)),
                new Stop("B",2,"B","UP",true,points.get(2)),new Stop("E",3,"E","DOWN",true,points.get(5)));
        var typed=new ArrayList<RouteShapeEvidence.TypedPoint>();
        for(int i=0;i<points.size();i++) {
            var point=points.get(i);
            typed.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(point.x(),point.y()),
                    i==1||i==4?2:i==3?3:1,i<=2?"UP":"DOWN",true));
        }
        var g=new RouteGeometry("204000057",1,"shared","A","B",2,stops,
                new Shape(new UUID(0,2),Instant.EPOCH,points,new RouteShapeEvidence("GBIS_API",
                        List.of(new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE",null)),typed)));
        var result=EvidenceRoutePaths.build(g);
        assertThat(result.up().points()).doesNotContain(p(0,10));
        assertThat(result.down().points()).doesNotContain(p(0,10));
        assertThat(RouteDisplayTopology.noNewContacts(g.paths(),result)).isTrue();
    }
    @Test void changedAlgorithmCannotReuseTheOldGenerationKey() {
        var g=reviewCase(false);String digest=RouteDisplayGeneration.input(g).digest();
        assertThat(RouteDisplayGeneration.key(digest,"evidence",AutomaticRouteDisplay.REVISION))
                .isNotEqualTo(RouteDisplayGeneration.key(digest,"evidence","automatic-quality-v6"));
        assertThat(RouteDisplayGeneration.SOURCE_REVISION).startsWith(AutomaticRouteDisplay.REVISION+"/");
    }
}
