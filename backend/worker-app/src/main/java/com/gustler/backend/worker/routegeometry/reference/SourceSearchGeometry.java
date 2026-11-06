package com.gustler.backend.worker.routegeometry.reference;

import static com.gustler.backend.worker.routegeometry.reference.ReferenceJson.require;
import com.gustler.backend.worker.routegeometry.reference.PinnedRouteSearchGraph.XY;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Native/engine planar arithmetic shared by candidate search and final fragment assembly; never WGS chainage. */
final class SourceSearchGeometry {
    private SourceSearchGeometry() {}
    record Projection(int segmentIndex,double fraction,XY point,double distance,double progress) {}
    static double distance(XY a,XY b) {return Math.hypot(a.x()-b.x(),a.y()-b.y());}
    static double length(List<XY> points) {double n=0;for(int i=1;i<points.size();i++)n+=distance(points.get(i-1),points.get(i));return n;}
    static XY at(XY a,XY b,double t) {return new XY(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t);}
    static XY at(List<XY> points,double progress) {
        double remaining=Math.max(0,Math.min(length(points),progress));
        for(int i=0;i<points.size()-1;i++) {var a=points.get(i);var b=points.get(i+1);double size=distance(a,b);
            if(size>0 && remaining<=size)return at(a,b,remaining/size);remaining-=size;}
        return points.getLast();
    }
    static Projection project(List<XY> points,XY query,boolean unique) {
        List<Projection> values=new ArrayList<>();double progress=0;
        for(int i=0;i<points.size()-1;i++) {
            var a=points.get(i);var b=points.get(i+1);double dx=b.x()-a.x(),dy=b.y()-a.y(),square=dx*dx+dy*dy;
            if(square==0)continue;
            double t=Math.max(0,Math.min(1,((query.x()-a.x())*dx+(query.y()-a.y())*dy)/square));
            XY at=at(a,b,t);values.add(new Projection(i,t,at,distance(query,at),progress+t*Math.sqrt(square)));
            progress+=Math.sqrt(square);
        }
        require(!values.isEmpty(),"DEGENERATE_GRAPH_GEOMETRY");
        values.sort(Comparator.comparingDouble(Projection::distance).thenComparingDouble(Projection::progress).thenComparingInt(Projection::segmentIndex));
        var best=values.getFirst();
        if(unique) for(var value:values) require(Math.abs(value.distance()-best.distance())>1e-9 || Math.abs(value.progress()-best.progress())<=.001,"AMBIGUOUS_SELECTED_OCCURRENCE_PROJECTION");
        return best;
    }
    static XY tangent(List<XY> points,double at,double window) {
        var a=at(points,Math.max(0,at-window));var b=at(points,Math.min(length(points),at+window));
        double norm=distance(a,b);return norm>1e-7?new XY((b.x()-a.x())/norm,(b.y()-a.y())/norm):new XY(0,0);
    }
    static Double angle(XY a,XY b) {
        double norm=Math.hypot(a.x(),a.y())*Math.hypot(b.x(),b.y());
        return norm>1e-7?Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(a.x()*b.x()+a.y()*b.y())/norm)))):null;
    }
}
