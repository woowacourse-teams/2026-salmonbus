package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.assertThat;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteDisplayTopologyEndpointTest {
    private static RoutePaths paths(Point a,Point b,Point c,Point d){return new RoutePaths(new RoutePaths.Path(List.of(a,b)),new RoutePaths.Path(List.of(c,d)));}
    private static Point p(double x,double y){return new Point(127+x/(111320*Math.cos(Math.toRadians(37))),37+y/111320);}

    @Test void exact1650SharedEndpointIsATouchInBothFrames(){
        var a=new Point(127.09547,37.53457);var b=new Point(127.09417,37.53648);var c=new Point(127.09562,37.53457);
        var geometry=paths(a,b,c,b);
        assertThat(RouteDisplayTopology.crossings(geometry).points()).isEmpty();
        assertThat(RouteDisplayTopology.rendererCrossings(geometry).points()).isEmpty();
        assertThat(RouteDisplayTopology.contacts(geometry).values()).anyMatch(t->t.first().equals(b)&&!t.overlap());
        assertThat(RouteDisplayTopology.rendererContacts(geometry).values()).hasSize(1);
    }
    @Test void distinctMicrometreNearEndpointProperCrossingIsStillDetected(){
        var a=p(0,0);var b=p(10,0);var c=p(10-.000001,-10);var d=p(10-.000001,.000001);
        assertThat(d).isNotEqualTo(b);var geometry=paths(a,b,c,d);
        assertThat(RouteDisplayTopology.intersection(a,b,c,d)).isNotNull();
        assertThat(RouteDisplayTopology.crossings(geometry).points()).hasSize(1);
        assertThat(RouteDisplayTopology.rendererCrossings(geometry).points()).hasSize(1);
    }
    @Test void newNonadjacentEndpointTouchStillBlocksContactGuard(){
        var before=paths(p(0,0),p(10,0),p(10,1),p(20,1));
        var after=paths(p(0,0),p(10,0),p(10,0),p(20,1));
        assertThat(RouteDisplayTopology.rendererCrossings(after).points()).isEmpty();
        assertThat(RouteDisplayTopology.noNewContacts(before,after)).isFalse();
    }
    @Test void collinearOverlapStillBlocksContactGuard(){
        var before=paths(p(0,0),p(10,0),p(5,1),p(15,1));
        var after=paths(p(0,0),p(10,0),p(5,0),p(15,0));
        assertThat(RouteDisplayTopology.rendererCrossings(after).points()).isEmpty();
        assertThat(RouteDisplayTopology.rendererContacts(after).values()).anyMatch(RouteDisplayTopology.Contact::overlap);
        assertThat(RouteDisplayTopology.noNewContacts(before,after)).isFalse();
    }
}
