package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.QualityIssue;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Stop;
import java.util.*;

/** Consecutive visit occurrences on the final serialized paths, never a lane accuracy claim. */
public final class RouteStopIntervals {
    private static final double COORDINATE_EPS_DEGREES = 1e-9;
    private static final double PROGRESS_EPS_METRES = .001;
    /** Stops are protected within eight source metres by the current curve policy.
     * Two centimetres only covers projection/serialization arithmetic in that
     * unchanged support. It does not relax the two metre curve leash or road proof. */
    public static final double PRESERVED_STOP_SOURCE_EPS_METRES = .02;

    public record Leg(String direction, double fromProgressMetres, double toProgressMetres,
                      double lengthMetres) {}
    public record Interval(String fromStationId, int fromSequence, String toStationId, int toSequence,
                           boolean turnSeam, List<Leg> legs, Double pathLengthMetres,
                           Double stopStraightMetres, Double pathToStopStraightRatio,
                           Double fromStopGapMetres, Double toStopGapMetres) {
        public Interval { legs = List.copyOf(legs); }
    }
    public record Result(List<Interval> intervals, List<QualityIssue> blockingIssues,
                         List<QualityIssue> diagnostics) {
        public Result { intervals=List.copyOf(intervals);blockingIssues=List.copyOf(blockingIssues);diagnostics=List.copyOf(diagnostics); }
        public boolean usable() { return blockingIssues.isEmpty(); }
    }
    public record Occurrence(String stationId, int sequence, String direction,
                             double beforeReferenceMetres, double afterReferenceMetres) {}
    public record OccurrenceCheck(List<Occurrence> occurrences, List<QualityIssue> blockingIssues) {
        public OccurrenceCheck { occurrences=List.copyOf(occurrences);blockingIssues=List.copyOf(blockingIssues); }
        public boolean usable() { return blockingIssues.isEmpty(); }
    }
    public record PreservedConnections(Map<Integer,Connection> connections, List<QualityIssue> blockingIssues) {
        public PreservedConnections { connections=Map.copyOf(connections);blockingIssues=List.copyOf(blockingIssues); }
        public boolean usable() { return blockingIssues.isEmpty(); }
    }
    private record Located(double progress, double gap) {}
    private RouteStopIntervals() {}

    public static Result evaluate(List<Stop> visits, RoutePaths paths, Map<Integer,Connection> connections) {
        var issues=new ArrayList<QualityIssue>();var diagnostics=new ArrayList<QualityIssue>();
        var intervals=new ArrayList<Interval>();var located=new HashMap<Integer,Located>();
        var chains=Map.of("UP",chain(paths.up().points()),"DOWN",chain(paths.down().points()));
        var sequences=new HashSet<Integer>();int previous=0;boolean down=false;
        for(var stop:visits) {
            if(!sequences.add(stop.stopSequence()) || stop.stopSequence()<=previous) issues.add(issue("STOP_INTERVAL_VISIT_ORDER_INVALID",stop,null));
            previous=stop.stopSequence();
            if(!Set.of("UP","DOWN").contains(stop.direction()) || down && stop.direction().equals("UP")) {
                issues.add(issue("STOP_INTERVAL_DIRECTION_ORDER_INVALID",stop,null));continue;
            }
            down|=stop.direction().equals("DOWN");
            if(stop.point()==null) {
                if(connections.containsKey(stop.stopSequence()))issues.add(issue("STOP_INTERVAL_CONNECTION_WITHOUT_SOURCE_COORDINATE",stop,null));
                diagnostics.add(issue("STOP_INTERVAL_SOURCE_COORDINATE_UNAVAILABLE",stop,null));continue;
            }
            var points=path(paths,stop.direction());var connection=connections.get(stop.stopSequence());
            if(!valid(points,connection)) {
                issues.add(issue(connection==null?"STOP_INTERVAL_CONNECTION_MISSING":"STOP_INTERVAL_CONNECTION_INVALID",stop,null));continue;
            }
            located.put(stop.stopSequence(),new Located(progress(chains.get(stop.direction()),connection),
                    RouteStopAlignment.distance(stop.point(),point(connection))));
        }
        for(int i=1;i<visits.size();i++) {
            var a=visits.get(i-1);var b=visits.get(i);var from=located.get(a.stopSequence());var to=located.get(b.stopSequence());
            boolean turn=!a.direction().equals(b.direction());var legs=new ArrayList<Leg>();Double length=null;
            boolean disconnected=turn && a.direction().equals("UP") && b.direction().equals("DOWN")
                    && !paths.up().points().getLast().equals(paths.down().points().getFirst());
            if(disconnected)issues.add(issue("STOP_INTERVAL_TURN_SEAM_DISCONNECTED",b,
                    RouteStopAlignment.distance(paths.up().points().getLast(),paths.down().points().getFirst())));
            if(from!=null && to!=null) {
                if(!turn) {
                    double delta=to.progress()-from.progress();
                    if(delta < -PROGRESS_EPS_METRES) issues.add(issue("STOP_INTERVAL_REVERSES",b,-delta));
                    else { length=Math.max(0,delta);legs.add(new Leg(a.direction(),from.progress(),to.progress(),length)); }
                } else if(!disconnected && a.direction().equals("UP") && b.direction().equals("DOWN")) {
                        double upEnd=chains.get("UP")[chains.get("UP").length-1];
                        legs.add(new Leg("UP",from.progress(),upEnd,Math.max(0,upEnd-from.progress())));
                        legs.add(new Leg("DOWN",0,to.progress(),to.progress()));
                        length=legs.stream().mapToDouble(Leg::lengthMetres).sum();
                }
            }
            Double straight=a.point()==null || b.point()==null?null:RouteStopAlignment.distance(a.point(),b.point());
            if(length!=null && length<=PROGRESS_EPS_METRES && straight!=null && straight>1)
                diagnostics.add(issue("STOP_INTERVAL_DISTANT_VISITS_SHARE_PROJECTION",b,straight));
            intervals.add(new Interval(a.stationId(),a.stopSequence(),b.stationId(),b.stopSequence(),turn,legs,length,
                    straight,length==null || straight==null || straight==0?null:length/straight,
                    from==null?null:from.gap(),to==null?null:to.gap()));
        }
        if(!sequences.containsAll(connections.keySet()))issues.add(new QualityIssue("STOP_INTERVAL_UNKNOWN_CONNECTION",null,null,null));
        return new Result(intervals,issues,diagnostics);
    }

    /** Compare occurrences in the same node-baseline frame, not vertex indices
     * or GPS gap: repeated visits may have identical coordinates on another lap. */
    public static OccurrenceCheck preservedCurveOccurrences(List<Stop> visits, RoutePaths reference,
            Map<Integer,Connection> before, RoutePaths candidate, Map<Integer,Connection> after,
            SourceBoundDisplayCurves.Plan plan) {
        var issues=new ArrayList<QualityIssue>();var occurrences=new ArrayList<Occurrence>();
        var referenceChains=new HashMap<String,double[]>();
        for(String direction:List.of("UP","DOWN")) {
            var mapping=plan.correspondences().get(direction);
            if(mapping==null || !mapping.referencePoints().equals(path(reference,direction))
                    || !mapping.displayPoints().equals(path(candidate,direction)) || !validMapping(mapping)) {
                issues.add(new QualityIssue("CURVE_STOP_SOURCE_MAPPING_INVALID",direction,null,null));
            } else referenceChains.put(direction,metricChain(mapping));
        }
        if(!issues.isEmpty())return new OccurrenceCheck(occurrences,issues);
        Map<Integer,Occurrence> bySequence=new HashMap<>();
        for(var stop:visits) {
            if(stop.point()==null)continue;
            var a=before.get(stop.stopSequence());var b=after.get(stop.stopSequence());var mapping=plan.correspondences().get(stop.direction());
            if(mapping==null || !valid(path(reference,stop.direction()),a) || !valid(path(candidate,stop.direction()),b)) {
                issues.add(issue("CURVE_STOP_SOURCE_CONNECTION_INVALID",stop,null));continue;
            }
            double old=progress(referenceChains.get(stop.direction()),a);var sourceAt=mapping.referenceProgressAtDisplayVertices();
            double now=sourceAt.get(b.segmentIndex())+b.segmentFraction()*(sourceAt.get(b.segmentIndex()+1)-sourceAt.get(b.segmentIndex()));
            var occurrence=new Occurrence(stop.stationId(),stop.stopSequence(),stop.direction(),old,now);
            occurrences.add(occurrence);bySequence.put(stop.stopSequence(),occurrence);
            if(Math.abs(now-old)>PRESERVED_STOP_SOURCE_EPS_METRES)
                issues.add(issue("CURVE_STOP_SOURCE_OCCURRENCE_CHANGED",stop,Math.abs(now-old)));
        }
        for(int i=1;i<visits.size();i++) {
            var a=bySequence.get(visits.get(i-1).stopSequence());var b=bySequence.get(visits.get(i).stopSequence());
            if(a!=null && b!=null && a.direction().equals(b.direction())
                    && b.beforeReferenceMetres()-a.beforeReferenceMetres()>PROGRESS_EPS_METRES
                    && b.afterReferenceMetres()-a.afterReferenceMetres()<=PROGRESS_EPS_METRES)
                issues.add(issue("CURVE_STOP_INTERVAL_COLLAPSED",visits.get(i),b.beforeReferenceMetres()-a.beforeReferenceMetres()));
        }
        return new OccurrenceCheck(occurrences,issues);
    }

    /** Transport each protected baseline occurrence through the source mapping.
     * The returned point is computed from the actual final serialized segment,
     * and must still coincide with the baseline road connection within 2cm.
     * The source stop pin is never an input to the point construction. */
    public static PreservedConnections preserveCurveConnections(List<Stop> visits,RoutePaths reference,
            Map<Integer,Connection> before,SourceBoundDisplayCurves.Plan plan) {
        var issues=new ArrayList<QualityIssue>();var result=new HashMap<Integer,Connection>();
        for(String direction:List.of("UP","DOWN")) {
            var mapping=plan.correspondences().get(direction);
            if(mapping==null || !mapping.referencePoints().equals(path(reference,direction))
                    || !mapping.displayPoints().equals(path(plan.paths(),direction)) || !validMapping(mapping))
                issues.add(new QualityIssue("CURVE_STOP_SOURCE_MAPPING_INVALID",direction,null,null));
        }
        if(!issues.isEmpty())return new PreservedConnections(result,issues);
        for(var stop:visits) {
            if(stop.point()==null)continue;
            var mapping=plan.correspondences().get(stop.direction());var connection=before.get(stop.stopSequence());
            if(mapping==null || !valid(path(reference,stop.direction()),connection)) {
                issues.add(issue("CURVE_STOP_SOURCE_CONNECTION_INVALID",stop,null));continue;
            }
            double s=progress(metricChain(mapping),connection);var values=mapping.referenceProgressAtDisplayVertices();
            if(s<values.getFirst() && values.getFirst()-s<=PRESERVED_STOP_SOURCE_EPS_METRES)s=values.getFirst();
            if(s>values.getLast() && s-values.getLast()<=PRESERVED_STOP_SOURCE_EPS_METRES)s=values.getLast();
            int found=Collections.binarySearch(values,s);int segment=found>=0?Math.min(found,values.size()-2):-found-2;
            if(segment<0 || segment+1>=values.size()) {
                issues.add(issue("CURVE_STOP_SOURCE_OCCURRENCE_OUTSIDE",stop,null));continue;
            }
            double fraction=(s-values.get(segment))/(values.get(segment+1)-values.get(segment));
            if(!Double.isFinite(fraction) || fraction<0 || fraction>1) {
                issues.add(issue("CURVE_STOP_SOURCE_OCCURRENCE_OUTSIDE",stop,null));continue;
            }
            var points=mapping.displayPoints();Point actual=RouteStopAlignment.interpolate(points.get(segment),points.get(segment+1),fraction);
            double error=metricDistance(actual,point(connection),mapping.metricFrame());
            if(error>PRESERVED_STOP_SOURCE_EPS_METRES) {
                issues.add(issue("CURVE_STOP_KNOT_NOT_PRESERVED",stop,error));continue;
            }
            result.put(stop.stopSequence(),new Connection(new com.gustler.backend.routegeometry.api.RouteDisplayInput.Point(actual.x(),actual.y()),segment,fraction));
        }
        return new PreservedConnections(result,issues);
    }
    private static boolean validMapping(SourceBoundDisplayCurves.Correspondence m) {
        var f=m.metricFrame();var values=m.referenceProgressAtDisplayVertices();
        if(f==null || !Double.isFinite(m.referenceLengthMetres()) || !Double.isFinite(f.xMetresPerDegree()) || !Double.isFinite(f.yMetresPerDegree())
                || f.xMetresPerDegree()<=0 || f.yMetresPerDegree()<=0 || values.size()!=m.displayPoints().size()
                || values.size()<2 || values.stream().anyMatch(v->v==null || !Double.isFinite(v)))return false;
        double[] chain=metricChain(m);double total=chain[chain.length-1];
        if(Math.abs(values.getFirst())>PRESERVED_STOP_SOURCE_EPS_METRES
                || Math.abs(values.getLast()-total)>PRESERVED_STOP_SOURCE_EPS_METRES
                || Math.abs(m.referenceLengthMetres()-total)>PRESERVED_STOP_SOURCE_EPS_METRES)return false;
        for(int i=1;i<values.size();i++)if(values.get(i)<=values.get(i-1))return false;
        return true;
    }
    private static double[] metricChain(SourceBoundDisplayCurves.Correspondence mapping) {
        var points=mapping.referencePoints();var f=mapping.metricFrame();double[] chain=new double[points.size()];
        for(int i=1;i<points.size();i++)chain[i]=chain[i-1]+Math.hypot((points.get(i).x()-points.get(i-1).x())*f.xMetresPerDegree(),
                (points.get(i).y()-points.get(i-1).y())*f.yMetresPerDegree());
        return chain;
    }
    private static double metricDistance(Point a,Point b,SourceBoundDisplayCurves.Frame frame) {
        return Math.hypot((a.x()-b.x())*frame.xMetresPerDegree(),(a.y()-b.y())*frame.yMetresPerDegree());
    }
    private static List<Point> path(RoutePaths paths,String direction) { return direction.equals("UP")?paths.up().points():paths.down().points(); }
    private static double[] chain(List<Point> points) {
        double[] chain=new double[points.size()];
        for(int i=1;i<points.size();i++)chain[i]=chain[i-1]+RouteStopAlignment.distance(points.get(i-1),points.get(i));
        return chain;
    }
    private static boolean valid(List<Point> points,Connection c) {
        if(c==null || c.segmentIndex()<0 || c.segmentIndex()>=points.size()-1
                || !Double.isFinite(c.segmentFraction()) || c.segmentFraction()<0 || c.segmentFraction()>1)return false;
        Point p=RouteStopAlignment.interpolate(points.get(c.segmentIndex()),points.get(c.segmentIndex()+1),c.segmentFraction());
        return Math.abs(p.x()-c.point().x())<=COORDINATE_EPS_DEGREES && Math.abs(p.y()-c.point().y())<=COORDINATE_EPS_DEGREES;
    }
    private static double progress(double[] chain,Connection c) { return chain[c.segmentIndex()]+c.segmentFraction()*(chain[c.segmentIndex()+1]-chain[c.segmentIndex()]); }
    private static Point point(Connection c) { return new Point(c.point().x(),c.point().y()); }
    private static QualityIssue issue(String code,Stop stop,Double metres) { return new QualityIssue(code,stop.direction(),stop.stopSequence(),metres); }
}
