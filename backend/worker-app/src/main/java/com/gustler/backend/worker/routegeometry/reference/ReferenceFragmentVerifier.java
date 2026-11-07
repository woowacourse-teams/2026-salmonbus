package com.gustler.backend.worker.routegeometry.reference;

import static com.gustler.backend.worker.routegeometry.reference.ReferenceJson.*;

import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.Crs;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.NativePoint;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.worker.routegeometry.reference.PinnedNativeCatalog.CatalogFeature;
import com.gustler.backend.worker.routegeometry.reference.PinnedNativeCatalog.NativeXYZ;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Checks an actual model fragment against server inputs. It never publishes a route or grants an accuracy grade. */
public final class ReferenceFragmentVerifier {
    // Arithmetic/serialization bounds, not fitted route tolerances or physical map accuracy claims.
    private static final double NATIVE_EPS=0.000001;
    private static final double WGS_EPS=0.00000001;
    // Independently measured Proj4J/pyproj conversion envelope in NativeReferenceProjection golden tests.
    private static final double PROJECTION_EPS=0.001;
    private static final Set<String> CANDIDATE_FIELDS=Set.of("routeId","inputDigest","rawSha256","sourceWindow","sourceRange",
        "directedLinks","nativeRange","candidateKind","nativeCoordinates","visits","generationScope","displayFragment","published","modelAdopted","accuracyGrade");
    private static final Set<String> FRAGMENT_FIELDS=Set.of("sourceWindow","sourceRange","directedLinks","nativeRange","nativeCoordinateSystem",
        "nativeCoordinatesXYZ","points","visits","rawBoundaryProjections","sourceOccurrenceTrace","sourceOccurrenceTraceVerified",
        "scopeGeometryStatus","entryExitVerified","operationScopeVerified","published","limits","accuracyGrade");
    public record SourceWindow(int startPointIndex,int endPointIndex) {
        public SourceWindow { require(startPointIndex>=0 && endPointIndex>startPointIndex,"INVALID_SOURCE_WINDOW"); }
    }
    public record Position(int segmentIndex,double segmentFraction) {
        public Position { require(segmentIndex>=0 && Double.isFinite(segmentFraction) && segmentFraction>=0 && segmentFraction<=1,"INVALID_SOURCE_POSITION"); }
        double ordinal() { return segmentIndex+segmentFraction; }
    }
    public record SourceRange(Position start,Position end) {
        public SourceRange { require(start!=null && end!=null && start.ordinal()<end.ordinal(),"INVALID_SOURCE_RANGE"); }
    }
    /** Exact fragment scope comes from the generation request, never from an evaluation answer or model flags. */
    public record RequestedScope(SourceWindow window,SourceRange range,List<Integer> requiredVisits) {
        public RequestedScope {
            require(window!=null && range!=null,"REQUEST_SCOPE_REQUIRED");requiredVisits=List.copyOf(requiredVisits);
            require(!requiredVisits.isEmpty() && requiredVisits.size()<=1000 && new HashSet<>(requiredVisits).size()==requiredVisits.size()
                && requiredVisits.stream().allMatch(v->v>0),"INVALID_REQUIRED_VISITS");
            require(range.start().ordinal()>=window.startPointIndex() && range.end().ordinal()<=window.endPointIndex(),"REQUEST_SCOPE_OUTSIDE_WINDOW");
        }
    }
    public record NativeRange(double startMeters,double endMeters) {
        public NativeRange { require(Double.isFinite(startMeters)&&Double.isFinite(endMeters)&&startMeters>=0&&endMeters>startMeters,"INVALID_NATIVE_RANGE"); }
    }
    /** Final emitted-segment arithmetic only; particularly a clipped endpoint is not a physical/lane visit proof. */
    public record VerifiedVisit(String stationId,int sequence,String direction,int edgeOccurrence,Connection pathConnection,double nativeProgressMeters) {
        public String verificationLevel() { return "STRUCTURAL_ONLY"; }
    }
    public record VerifiedFragment(String routeId,String inputDigest,String rawSha256,String candidateSha256,String referenceBundleSha256,
            SourceWindow sourceWindow,SourceRange sourceRange,List<String> directedLinks,NativeRange nativeRange,
            List<NativeXYZ> nativeCoordinates,List<RouteGeometry.Point> points,List<VerifiedVisit> visits,
            boolean sourceProjectionTraceVerified,double maxSourceDistanceMeters) {
        public VerifiedFragment {
            directedLinks=List.copyOf(directedLinks);nativeCoordinates=List.copyOf(nativeCoordinates);points=List.copyOf(points);visits=List.copyOf(visits);
        }
        public String accuracyGrade() { return "UNEVALUATED"; }
        public boolean published() { return false; }
        public boolean fragmentOnly() { return true; }
        public String associationStatus() { return "UNKNOWN"; }
        public String operationStatus() { return "UNKNOWN"; }
        public String entryExitStatus() { return "UNKNOWN"; }
    }
    public ReferenceFragmentVerifier(ObjectMapper mapper) { java.util.Objects.requireNonNull(mapper); }

    public VerifiedFragment verify(RouteGeometry server,byte[] raw,RequestedScope requested,byte[] packet,PinnedNativeCatalog catalog) {
        require(server!=null && server.shape()!=null && requested!=null && catalog!=null,"SERVER_INPUTS_REQUIRED");
        var source=read(raw,4_000_000);var candidate=read(packet,1_000_000);fields(candidate,CANDIDATE_FIELDS);noResourceReferences(candidate);
        var fragment=candidate.path("displayFragment");fields(fragment,FRAGMENT_FIELDS);
        RouteDisplayInput rawInput=rawInput(source),current=RouteDisplayGeneration.input(server);
        String rawSha=RouteSourceReceipt.sha256(raw);
        require(rawInput.equals(current) && rawSha.equals(text(candidate,"rawSha256"))
            && current.digest().equals(text(candidate,"inputDigest")) && server.routeId().equals(text(candidate,"routeId")),"SOURCE_IDENTITY_MISMATCH");
        SourceWindow window=window(candidate.path("sourceWindow"));SourceRange range=range(candidate.path("sourceRange"));
        require(window.equals(requested.window()) && range.equals(requested.range())
            && window.equals(window(fragment.path("sourceWindow"))) && range.equals(range(fragment.path("sourceRange"))),"REQUEST_SCOPE_MISMATCH");
        require(window.endPointIndex()<server.points().size() && range.end().segmentIndex()<server.points().size()-1,"SOURCE_SCOPE_OUT_OF_BOUNDS");
        List<String> ids=ids(candidate.path("directedLinks"));require(ids.equals(ids(fragment.path("directedLinks"))),"FRAGMENT_LINKS_MISMATCH");
        NativeRange nativeRange=nativeRange(candidate.path("nativeRange"));require(nativeRange.equals(nativeRange(fragment.path("nativeRange"))),"FRAGMENT_NATIVE_RANGE_MISMATCH");
        List<CatalogFeature> edges=new ArrayList<>();List<NativeXYZ> chain=new ArrayList<>();List<Double> offsets=new ArrayList<>();double cumulative=0;
        Crs crs=null;CatalogFeature before=null;
        for(String id:ids) {
            var edge=catalog.selected(id).orElseThrow(()->new IllegalArgumentException("UNKNOWN_NATIVE_FEATURE"));
            if(crs==null) crs=edge.feature().crs();
            require(crs.equals(edge.feature().crs()),"MIXED_NATIVE_CRS");
            if(before!=null) {
                require(before.feature().namespace().equals(edge.feature().namespace()) && before.feature().toNode().equals(edge.feature().fromNode()),"NATIVE_TOPOLOGY_DISCONNECTED");
                require(before.xyz().getLast().equals(edge.xyz().getFirst()),"NATIVE_JOIN_GAP");
            }
            offsets.add(cumulative);edges.add(edge);
            chain.addAll(before==null?edge.xyz():edge.xyz().subList(1,edge.xyz().size()));
            cumulative+=NativeReferenceProjection.nativeLength(crs,edge.feature().vertices());before=edge;
        }
        require(("EPSG:"+crs.code()).equals(text(fragment,"nativeCoordinateSystem")),"CANDIDATE_CRS_MISMATCH");
        require(nativeRange.endMeters()<=cumulative && nativeRange.startMeters()<NativeReferenceProjection.nativeLength(crs,edges.getFirst().feature().vertices())
            && nativeRange.endMeters()>offsets.getLast(),"NATIVE_RANGE_OUT_OF_BOUNDS");
        List<NativeXYZ> clipped=clip(chain,nativeRange);List<NativeXYZ> submitted=PinnedNativeCatalog.xyz(fragment.path("nativeCoordinatesXYZ"));
        sameNative(clipped,submitted);sameNative(clipped,PinnedNativeCatalog.xyz(candidate.path("nativeCoordinates")));
        List<RouteGeometry.Point> projected=clipped.stream().map(p->NativeReferenceProjection.toWgs84(edges.getFirst().feature().crs(),p.xy())).toList();
        var emitted=points(fragment.path("points"));require(emitted.size()==projected.size(),"FRAGMENT_VERTEX_COUNT_MISMATCH");
        for(int i=0;i<emitted.size();i++) samePoint(emitted.get(i),projected.get(i),WGS_EPS,"CANDIDATE_WGS_PROJECTION_MISMATCH");
        var visits=visits(fragment.path("visits"),candidate.path("visits"),requested,server,emitted,clipped,crs,nativeRange,offsets,edges);
        boundary(fragment.path("rawBoundaryProjections").path("start"),rawAt(server.points(),range.start()),edges.getFirst(),nativeRange.startMeters(),crs);
        boundary(fragment.path("rawBoundaryProjections").path("end"),rawAt(server.points(),range.end()),edges.getLast(),nativeRange.endMeters()-offsets.getLast(),crs);
        double maxDistance=trace(fragment.path("sourceOccurrenceTrace"),server,window,range,clipped,crs);
        return new VerifiedFragment(server.routeId(),current.digest(),rawSha,RouteSourceReceipt.sha256(packet),catalog.bundleDigest(),window,range,ids,nativeRange,
            clipped,emitted,visits,Double.isFinite(maxDistance),Double.isFinite(maxDistance)?maxDistance:0);
    }

    private static List<VerifiedVisit> visits(JsonNode rows,JsonNode identities,RequestedScope requested,RouteGeometry server,
            List<RouteGeometry.Point> points,List<NativeXYZ> nativePoints,Crs crs,NativeRange range,List<Double> offsets,List<CatalogFeature> edges) {
        require(rows.isArray() && identities.isArray() && rows.size()==requested.requiredVisits().size() && identities.size()==rows.size(),"REQUIRED_VISITS_MISMATCH");
        Map<Integer,RouteGeometry.Stop> stops=new HashMap<>();server.busStops().forEach(s->stops.put(s.stopSequence(),s));
        var xy=nativePoints.stream().map(NativeXYZ::xy).toList();List<VerifiedVisit> result=new ArrayList<>();double previous=-1;
        for(int n=0;n<rows.size();n++) {
            var row=rows.get(n);int sequence=integer(row.path("sequence")),occurrence=integer(row.path("edgeOccurrence"));
            require(sequence==requested.requiredVisits().get(n),"REQUIRED_VISIT_ORDER_MISMATCH");var stop=stops.get(sequence);
            require(stop!=null && stop.point()!=null && stop.stationId().equals(text(row,"stationId")) && stop.direction().equals(text(row,"direction")),"VISIT_IDENTITY_MISMATCH");
            samePoint(stop.point(),point(row.path("sourcePoint")),0,"VISIT_SOURCE_POINT_MISMATCH");
            var identity=identities.get(n);
            require(sequence==integer(identity.path("sequence")) && occurrence==integer(identity.path("edgeOccurrence"))
                && stop.stationId().equals(text(identity,"stationId")) && stop.direction().equals(text(identity,"direction")),"TOP_LEVEL_VISIT_MISMATCH");
            require(occurrence>=0 && occurrence<edges.size(),"INVALID_EDGE_OCCURRENCE");
            var c=row.path("pathConnection");int index=integer(c.path("segmentIndex"));double fraction=number(c.path("segmentFraction"));
            require(index>=0 && index<points.size()-1 && fraction>=0 && fraction<=1,"INVALID_FINAL_CONNECTION");
            var connected=point(c.path("point"));samePoint(connected,interpolate(points.get(index),points.get(index+1),fraction),1e-9,"FINAL_CONNECTION_INTERPOLATION_MISMATCH");
            double progress=index+fraction;require(progress+1e-9>=previous,"VISIT_ORDER_MISMATCH");previous=progress;
            double nativeProgress=NativeReferenceProjection.nativeChainage(crs,xy,index,fraction);
            require(Math.abs(nativeProgress-number(row.path("nativeProgressMeters")))<=PROJECTION_EPS,"VISIT_NATIVE_PROGRESS_MISMATCH");
            double absolute=range.startMeters()+nativeProgress,limit=offsets.get(occurrence)+NativeReferenceProjection.nativeLength(crs,edges.get(occurrence).feature().vertices());
            require(absolute+NATIVE_EPS>=offsets.get(occurrence) && absolute<=limit+NATIVE_EPS,"VISIT_EDGE_OCCURRENCE_MISMATCH");
            result.add(new VerifiedVisit(stop.stationId(),sequence,stop.direction(),occurrence,
                new Connection(new RouteDisplayInput.Point(connected.x(),connected.y()),index,fraction),nativeProgress));
        }
        return List.copyOf(result);
    }
    private static void boundary(JsonNode row,RouteGeometry.Point source,CatalogFeature feature,double expectedProgress,Crs crs) {
        int i=integer(row.path("segmentIndex"));double t=number(row.path("segmentFraction"));var vertices=feature.feature().vertices();
        NativePoint p=NativeReferenceProjection.nativePointAt(vertices,i,t);sameXY(p,row.path("point"),NATIVE_EPS,"RAW_BOUNDARY_POINT_MISMATCH");
        double progress=NativeReferenceProjection.nativeChainage(crs,vertices,i,t);
        require(Math.abs(progress-expectedProgress)<=NATIVE_EPS && Math.abs(progress-number(row.path("progressMeters")))<=NATIVE_EPS,"RAW_BOUNDARY_PROGRESS_MISMATCH");
        NativePoint raw=NativeReferenceProjection.fromWgs84(crs,source);double distance=Math.hypot(raw.x()-p.x(),raw.y()-p.y());
        require(Math.abs(distance-number(row.path("distanceMeters")))<=PROJECTION_EPS,"RAW_BOUNDARY_DISTANCE_MISMATCH");
        var nearest=nearest(vertices,raw,0);
        require(Math.abs(nearest.progress()-progress)<=PROJECTION_EPS,"RAW_BOUNDARY_NOT_NEAREST");
    }
    private record Projection(double progress,double distance) {}
    private static double trace(JsonNode rows,RouteGeometry server,SourceWindow window,SourceRange range,List<NativeXYZ> clipped,Crs crs) {
        if(rows.isMissingNode() || rows.isNull()) return Double.NaN;
        int first=(int)Math.ceil(range.start().ordinal()),last=(int)Math.floor(range.end().ordinal());
        require(rows.isArray() && rows.size()==last-first+1,"SOURCE_TRACE_SCOPE_MISMATCH");
        var vertices=clipped.stream().map(NativeXYZ::xy).toList();double previous=0,max=0;
        for(int n=0;n<rows.size();n++) {
            var row=rows.get(n);int sourceIndex=integer(row.path("sourcePointIndex"));
            require(sourceIndex==first+n && sourceIndex>=window.startPointIndex() && sourceIndex<=window.endPointIndex(),"SOURCE_TRACE_INDEX_MISMATCH");
            int segment=integer(row.path("segmentIndex"));double fraction=number(row.path("segmentFraction"));
            var p=NativeReferenceProjection.nativePointAt(vertices,segment,fraction);sameXY(p,row.path("point"),NATIVE_EPS,"SOURCE_TRACE_POINT_MISMATCH");
            double progress=NativeReferenceProjection.nativeChainage(crs,vertices,segment,fraction);
            require(progress+NATIVE_EPS>=previous && Math.abs(progress-number(row.path("progressMeters")))<=NATIVE_EPS,"SOURCE_TRACE_PROGRESS_MISMATCH");
            var raw=NativeReferenceProjection.fromWgs84(crs,server.points().get(sourceIndex));double distance=Math.hypot(raw.x()-p.x(),raw.y()-p.y());
            require(Math.abs(distance-number(row.path("distanceMeters")))<=PROJECTION_EPS,"SOURCE_TRACE_DISTANCE_MISMATCH");
            var nearest=nearest(vertices,raw,previous);
            require(Math.abs(nearest.progress()-progress)<=PROJECTION_EPS,"SOURCE_TRACE_NOT_MONOTONE_NEAREST");
            previous=progress;max=Math.max(max,distance);
        }
        return max;
    }
    private static Projection nearest(List<NativePoint> vertices,NativePoint p,double floor) {
        double at=0,best=Double.POSITIVE_INFINITY,selected=0;
        for(int i=0;i<vertices.size()-1;i++) {
            var a=vertices.get(i);var b=vertices.get(i+1);double dx=b.x()-a.x(),dy=b.y()-a.y(),length=Math.hypot(dx,dy);
            if(length>0 && at+length>=floor) {
                double t=Math.max(Math.max(0,(floor-at)/length),Math.min(1,((p.x()-a.x())*dx+(p.y()-a.y())*dy)/(length*length)));
                t=Math.min(1,t);double d=Math.hypot(p.x()-(a.x()+t*dx),p.y()-(a.y()+t*dy));
                if(d<best) { best=d;selected=at+t*length; }
            }
            at+=length;
        }
        require(Double.isFinite(best),"SOURCE_TRACE_PROJECTION_UNAVAILABLE");return new Projection(selected,best);
    }
    private static List<NativeXYZ> clip(List<NativeXYZ> vertices,NativeRange range) {
        List<NativeXYZ> result=new ArrayList<>();double at=0;
        for(int i=0;i<vertices.size()-1;i++) {
            var a=vertices.get(i);var b=vertices.get(i+1);double length=Math.hypot(b.x()-a.x(),b.y()-a.y()),next=at+length;
            require(length>0,"ZERO_NATIVE_SEGMENT_UNSUPPORTED");
            if(next>=range.startMeters() && at<=range.endMeters()) {
                double start=Math.max(0,(range.startMeters()-at)/length),end=Math.min(1,(range.endMeters()-at)/length);
                if(start<=end) {
                    var first=start==0?a:start==1?b:a.at(b,start);var last=end==0?a:end==1?b:a.at(b,end);
                    if(result.isEmpty()) result.add(first);
                    if(!result.getLast().equals(last)) result.add(last);
                }
            }
            at=next;if(at>range.endMeters()) break;
        }
        require(result.size()>=2,"EMPTY_NATIVE_FRAGMENT");return List.copyOf(result);
    }
    private static void sameNative(List<NativeXYZ> expected,List<NativeXYZ> actual) {
        require(expected.size()==actual.size(),"NATIVE_VERTEX_COUNT_MISMATCH");
        for(int i=0;i<expected.size();i++) {var a=expected.get(i);var b=actual.get(i);
            require(Math.abs(a.x()-b.x())<=NATIVE_EPS && Math.abs(a.y()-b.y())<=NATIVE_EPS && Math.abs(a.z()-b.z())<=NATIVE_EPS,"NATIVE_GEOMETRY_MISMATCH");}
    }
    private static void samePoint(RouteGeometry.Point a,RouteGeometry.Point b,double eps,String error) {
        require(Math.abs(a.x()-b.x())<=eps && Math.abs(a.y()-b.y())<=eps,error);
    }
    private static void sameXY(NativePoint p,JsonNode row,double eps,String error) {
        require(row.isArray()&&row.size()==2 && Math.abs(p.x()-number(row.get(0)))<=eps && Math.abs(p.y()-number(row.get(1)))<=eps,error);
    }
    private static RouteGeometry.Point rawAt(List<RouteGeometry.Point> p,Position at) {return interpolate(p.get(at.segmentIndex()),p.get(at.segmentIndex()+1),at.segmentFraction());}
    private static RouteGeometry.Point interpolate(RouteGeometry.Point a,RouteGeometry.Point b,double t) {return new RouteGeometry.Point(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t);}
    private static RouteGeometry.Point point(JsonNode n) {return new RouteGeometry.Point(number(n.path("x")),number(n.path("y")));}
    private static List<RouteGeometry.Point> points(JsonNode n) {
        require(n.isArray() && n.size()>=2 && n.size()<=100_000,"INVALID_POINTS");var out=new ArrayList<RouteGeometry.Point>();for(var p:n)out.add(point(p));return List.copyOf(out);
    }
    private static SourceWindow window(JsonNode n) {return new SourceWindow(integer(n.path("startPointIndex")),integer(n.path("endPointIndex")));}
    private static Position position(JsonNode n) {return new Position(integer(n.path("segmentIndex")),number(n.path("segmentFraction")));}
    private static SourceRange range(JsonNode n) {return new SourceRange(position(n.path("start")),position(n.path("end")));}
    private static NativeRange nativeRange(JsonNode n) {return new NativeRange(number(n.path("startMeters")),number(n.path("endMeters")));}
    private static List<String> ids(JsonNode n) {
        require(n.isArray() && n.size()>0 && n.size()<=64,"INVALID_LINK_OCCURRENCES");var out=new ArrayList<String>();for(var id:n){require(id.isString()&&!id.asString().isBlank(),"INVALID_LINK_ID");out.add(id.asString());}return List.copyOf(out);
    }
    private static RouteDisplayInput rawInput(JsonNode n) {
        var p=points(n.path("points")).stream().map(x->new RouteDisplayInput.Point(x.x(),x.y())).toList();
        var rows=n.path("busStops");require(rows.isArray() && rows.size()<=5000,"INVALID_SOURCE_VISITS");var visits=new ArrayList<RouteDisplayInput.Visit>();
        for(var stop:rows) {var at=stop.path("point");require(stop.path("boardingAllowed").isBoolean(),"INVALID_BOARDING_ROLE");
            visits.add(new RouteDisplayInput.Visit(text(stop,"stationId"),integer(stop.path("stopSequence")),text(stop,"direction"),stop.path("boardingAllowed").asBoolean(),
                at.isNull()?null:new RouteDisplayInput.Point(number(at.path("x")),number(at.path("y")))));}
        var turn=n.path("turningPoint");return new RouteDisplayInput(text(n,"routeId"),turn.isNull()?null:integer(turn.path("stopSequence")),p,visits);
    }
    private static void noResourceReferences(JsonNode node) {
        if(node.isString()) {
            String v=node.asString();require(!v.startsWith("/") && !v.startsWith("./") && !v.startsWith("../")
                && !v.matches("(?i)^(?:https?|file)://.*") && !v.matches("^[A-Za-z]:[\\\\/].*"),"EXTERNAL_REFERENCE_FORBIDDEN");
        } else if((node.isArray() || node.isObject())) for(var child:node) noResourceReferences(child);
    }
}
