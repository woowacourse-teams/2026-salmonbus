package com.gustler.backend.worker.routegeometry.reference;

import static com.gustler.backend.worker.routegeometry.reference.ReferenceJson.require;
import static com.gustler.backend.worker.routegeometry.reference.SourceSearchGeometry.*;

import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteModelFacts;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.Crs;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.NativePoint;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.worker.routegeometry.reference.PinnedNativeCatalog.NativeXYZ;
import com.gustler.backend.worker.routegeometry.reference.PinnedRouteSearchGraph.Edge;
import com.gustler.backend.worker.routegeometry.reference.PinnedRouteSearchGraph.XY;
import com.gustler.backend.worker.routegeometry.reference.ReferenceFragmentVerifier.NativeRange;
import com.gustler.backend.worker.routegeometry.reference.ReferenceFragmentVerifier.Position;
import com.gustler.backend.worker.routegeometry.reference.ReferenceFragmentVerifier.SourceRange;
import com.gustler.backend.worker.routegeometry.reference.ReferenceFragmentVerifier.SourceWindow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Actual Java candidate search. Nothing here adopts a model, authorizes operation, or replaces a served route. */
public final class SourceRoleNativeGenerator {
    public static final String REVISION="JAVA_SOURCE_ROLE_NATIVE_REFINEMENT_V1";
    public record Config(double radiusMeters,int candidateCountPerVisit,double headingDegreesMaximum,
            double lengthRatioLimit,double lengthSlackMeters,int maxExpandedNodes,double tangentWindowMeters,
            double emissionSigmaMeters,double headingScaleDegrees) {
        public Config {
            require(radiusMeters==45 && candidateCountPerVisit==4 && headingDegreesMaximum==105
                && lengthRatioLimit==2.5 && lengthSlackMeters==100 && maxExpandedNodes==3000
                && tangentWindowMeters==5 && emissionSigmaMeters==8 && headingScaleDegrees==50,"UNSUPPORTED_UNPINNED_GENERATOR_CONFIGURATION");
        }
        public static Config frozenV3() {return new Config(45,4,105,2.5,100,3000,5,8,50);}
        public String digest() {return RouteDisplayGeneration.sha256(REVISION+"\n"+toString()+"\nEMISSION_GLOBAL_INDEX_TIEBREAK/EDGE_STATE_DIJKSTRA");}
    }
    public record Visit(String stationId,int sequence,String direction,RouteGeometry.Point sourcePoint,
            int edgeOccurrence,Connection pathConnection,double nativeProgressMeters,double unclippedNativeProgressMeters,
            boolean scopeClamped,double scopeClampDistanceMeters,String scopeClampBoundary,double sourceStationOffsetMeters) {
        public String verificationLevel() {return "STRUCTURAL_ONLY";}
    }
    public record SourceTrace(int sourcePointIndex,String providerRole,int segmentIndex,double segmentFraction,
            List<Double> point,double distanceMeters,double progressMeters,boolean orderingConstraintApplied) {
        public SourceTrace {point=List.copyOf(point);}
    }
    public record BoundaryProjection(int segmentIndex,double segmentFraction,List<Double> point,double distanceMeters,double progressMeters) {
        public BoundaryProjection {point=List.copyOf(point);}
    }
    public record Fragment(SourceWindow sourceWindow,SourceRange sourceRange,List<String> directedLinks,NativeRange nativeRange,
            String nativeCoordinateSystem,List<NativeXYZ> nativeCoordinatesXYZ,List<RouteGeometry.Point> points,List<Visit> visits,
            Map<String,BoundaryProjection> rawBoundaryProjections,List<SourceTrace> sourceOccurrenceTrace) {
        public Fragment {directedLinks=List.copyOf(directedLinks);nativeCoordinatesXYZ=List.copyOf(nativeCoordinatesXYZ);points=List.copyOf(points);
            visits=List.copyOf(visits);rawBoundaryProjections=Map.copyOf(rawBoundaryProjections);sourceOccurrenceTrace=List.copyOf(sourceOccurrenceTrace);}
        public boolean published() {return false;}
        public String accuracyGrade() {return "UNEVALUATED";}
        public String associationStatus() {return "UNKNOWN";}
        public String operationStatus() {return "UNKNOWN";}
        public String entryExitStatus() {return "UNKNOWN";}
        public boolean wholeRouteCoverageVerified() {return false;}
    }
    public record Result(String status,String reason,String algorithm,String configDigest,String inputDigest,String factsDigest,
            String graphDigest,String rawSha256,int graphEdgeCount,List<Integer> laneCandidateCounts,List<String> coarseRoadOccurrences,
            int nativePathOptions,Optional<Fragment> candidate,List<RouteGeometry.Point> sourceFallbackPoints) {
        public Result {laneCandidateCounts=List.copyOf(laneCandidateCounts);coarseRoadOccurrences=List.copyOf(coarseRoadOccurrences);
            sourceFallbackPoints=List.copyOf(sourceFallbackPoints);}
        public boolean published() {return false;}
        public String accuracyGrade() {return "UNEVALUATED";}
    }
    private record Seed(Edge edge,Projection projection,Double headingError,double emission) {}
    private record Range(Edge edge,double start,double end) {}
    private record Path(double distance,List<Range> ranges,double score) {Path {ranges=List.copyOf(ranges);}}
    private record State(String node,int prior) {}
    private record QueueState(double cost,State state) {}
    private record Previous(State state,int edge) {}
    private record Middle(double distance,List<Edge> edges) {}
    private final Config config;
    public SourceRoleNativeGenerator(Config config) {this.config=java.util.Objects.requireNonNull(config);}

    public Result generate(RouteGeometry source,RouteModelFacts facts,PinnedRouteSearchGraph graph,int firstSequence,int lastSequence) {
        String input=RouteDisplayGeneration.input(source).digest();
        require(source.routeId().equals(graph.tree().path("scope").path("routeId").asString())
            && input.equals(graph.tree().path("scope").path("inputDigest").asString()),"SEARCH_SOURCE_INPUT_MISMATCH");
        ProviderSourceBinding.Binding binding;
        try {binding=ProviderSourceBinding.bind(source,facts,graph.providerWeb());}
        catch(IllegalArgumentException unavailable) {return fallback(source,facts,graph,input,unavailable.getMessage(),List.of(),List.of(),0);}
        var bound=binding.visits();int pos=-1;
        for(int i=0;i<bound.size();i++) if(bound.get(i).sequence()==firstSequence)pos=i;
        require(pos>=0 && pos+1<bound.size() && bound.get(pos+1).sequence()==lastSequence,"REQUEST_MUST_BE_CONSECUTIVE_SOURCE_VISITS");
        var from=bound.get(pos);var to=bound.get(pos+1);
        if(!from.direction().equals(to.direction()))return fallback(source,facts,graph,input,"SOURCE_DIRECTION_BOUNDARY",List.of(),List.of(),0);
        if(!"Y".equals(from.centerYn()) || !"Y".equals(to.centerYn()))return fallback(source,facts,graph,input,"NOT_A_CONSECUTIVE_CENTRAL_STOP_PAIR",List.of(),List.of(),0);
        int start=from.sourcePointIndex(),end=to.sourcePointIndex();
        require(start<end,"INVALID_SOURCE_INTERVAL");
        RouteGeometry.Stop a=source.busStops().get(pos),b=source.busStops().get(pos+1);
        if(a.point()==null || b.point()==null)return fallback(source,facts,graph,input,"SOURCE_STATION_COORDINATE_MISSING",List.of(),List.of(),0);
        var sourceXY=java.util.stream.IntStream.rangeClosed(start,end).mapToObj(i->graph.sourcePoint(graph.engineCrs(),i,source.points().get(i))).toList();
        double sourceLength=length(sourceXY),limit=sourceLength*config.lengthRatioLimit()+config.lengthSlackMeters();
        if(sourceLength==0)return fallback(source,facts,graph,input,"DEGENERATE_SOURCE_INTERVAL",List.of(),List.of(),0);
        var stationA=graph.sourceVisit(graph.engineCrs(),a);var stationB=graph.sourceVisit(graph.engineCrs(),b);
        graph.assertSearchCovered(firstSequence,lastSequence,start,end,limit,List.of(stationA,stationB));
        Map<String,String> nativeKinds=new HashMap<>();Set<String> roads=new HashSet<>();
        for(Edge edge:graph.edges()) {
            if(edge.source().equals("ITS") && busEdge(edge))roads.add(edge.id());
            String kind=nativeKind(edge);if(kind!=null)nativeKinds.put(edge.id(),kind);
        }
        var poolA=candidates(graph,stationA,tangent(sourceXY,0,config.tangentWindowMeters()));
        var poolB=candidates(graph,stationB,tangent(sourceXY,sourceLength,config.tangentWindowMeters()));
        var lanesA=poolA.stream().filter(s->"BUS_LANE".equals(nativeKinds.get(s.edge().id()))).limit(config.candidateCountPerVisit()).toList();
        var lanesB=poolB.stream().filter(s->"BUS_LANE".equals(nativeKinds.get(s.edge().id()))).limit(config.candidateCountPerVisit()).toList();
        var counts=List.of(lanesA.size(),lanesB.size());
        var roadA=poolA.stream().filter(s->roads.contains(s.edge().id())).limit(config.candidateCountPerVisit()).toList();
        var roadB=poolB.stream().filter(s->roads.contains(s.edge().id())).limit(config.candidateCountPerVisit()).toList();
        var turnRows=graph.tree().path("turnRows");
        List<Path> coarse=new ArrayList<>();
        for(var x:roadA)for(var y:roadB)connect(graph,x,y,roads,"ITS",sourceLength,true,turnRows).ifPresent(coarse::add);
        coarse.sort(SourceRoleNativeGenerator::comparePaths);
        if(coarse.isEmpty())return fallback(source,facts,graph,input,"NO_ROAD_COURSE_IN_BOUNDED_SUPPORTED_SEARCH",counts,List.of(),0);
        List<String> coarseIds=coarse.getFirst().ranges().stream().map(r->r.edge().id()).toList();
        Set<String> coarseSet=new HashSet<>(coarseIds),retrievalSeeds=retrievalSeeds(graph.tree().path("reviewedAssociations"),coarseIds);
        Set<String> associated=new HashSet<>();
        for(Edge edge:graph.edges()) if(nativeKinds.containsKey(edge.id()) && (coarseSet.contains(referenceId(edge)) || retrievalSeeds.contains(edge.id())))associated.add(edge.id());
        List<Path> options=new ArrayList<>();
        for(var x:lanesA)for(var y:lanesB) {
            String ns=x.edge().nativeFeature().namespace();
            if(ns.equals(y.edge().nativeFeature().namespace()))connect(graph,x,y,associated,ns,sourceLength,false,turnRows).ifPresent(options::add);
        }
        options.sort(SourceRoleNativeGenerator::comparePaths);
        if(options.isEmpty())return fallback(source,facts,graph,input,"NO_NATIVE_PATH_IN_BOUNDED_SUPPORTED_SEARCH",counts,coarseIds,0);
        try {
            Fragment fragment=buildFragment(source,binding,options.getFirst(),a,b,start,end,graph);
            return new Result("CANDIDATE_ONLY","OPERATION_ASSOCIATION_ENTRY_EXIT_AND_WHOLE_COVERAGE_UNRESOLVED",REVISION,
                config.digest(),input,facts.factsDigest(),graph.digest(),graph.rawSourceSha(),graph.edges().size(),counts,coarseIds,options.size(),Optional.of(fragment),source.points());
        } catch(IllegalArgumentException unavailable) {return fallback(source,facts,graph,input,unavailable.getMessage(),counts,coarseIds,options.size());}
    }
    private Result fallback(RouteGeometry source,RouteModelFacts facts,PinnedRouteSearchGraph graph,String input,String reason,List<Integer> counts,List<String> coarse,int options) {
        require(reason!=null && reason.matches("[A-Z0-9_]{1,128}"),"UNEXPECTED_GENERATION_FAILURE");
        return new Result("SOURCE_FALLBACK",reason,REVISION,config.digest(),input,facts==null?"UNRESOLVED":facts.factsDigest(),graph.digest(),
            graph.rawSourceSha(),graph.edges().size(),counts,coarse,options,Optional.empty(),source.points());
    }
    private List<Seed> candidates(PinnedRouteSearchGraph graph,XY point,XY heading) {
        List<Seed> values=new ArrayList<>();
        for(Edge edge:graph.edges()) {
            Projection p=SourceSearchGeometry.project(edge.pointsXY(),point,false);
            if(p.distance()>config.radiusMeters())continue;
            Double error=angle(heading,tangent(edge.pointsXY(),p.progress(),config.tangentWindowMeters()));
            if(error!=null && error>config.headingDegreesMaximum())continue;
            double emission=Math.pow(p.distance()/config.emissionSigmaMeters(),2)+Math.pow((error==null?0:error)/config.headingScaleDegrees(),2);
            values.add(new Seed(edge,p,error,emission));
        }
        values.sort(Comparator.comparingDouble(Seed::emission).thenComparingInt(s->s.edge().index()));return List.copyOf(values);
    }
    private Optional<Path> connect(PinnedRouteSearchGraph graph,Seed a,Seed b,Set<String> allowed,String namespace,double sourceLength,boolean coarse,JsonNode turns) {
        if(!allowed.contains(a.edge().id()) || !allowed.contains(b.edge().id()))return Optional.empty();
        double limit=sourceLength*config.lengthRatioLimit()+config.lengthSlackMeters(),distance;List<Range> ranges=new ArrayList<>();
        if(a.edge().index()==b.edge().index() && a.projection().progress()<=b.projection().progress()) {
            distance=b.projection().progress()-a.projection().progress();ranges.add(new Range(a.edge(),a.projection().progress(),b.projection().progress()));
        } else {
            var middle=shortest(graph,a.edge(),b.edge(),allowed,namespace,limit,coarse,turns);
            if(middle.isEmpty())return Optional.empty();
            distance=length(a.edge().pointsXY())-a.projection().progress()+middle.get().distance()+b.projection().progress();
            ranges.add(new Range(a.edge(),a.projection().progress(),length(a.edge().pointsXY())));
            for(Edge edge:middle.get().edges())ranges.add(new Range(edge,0,length(edge.pointsXY())));
            ranges.add(new Range(b.edge(),0,b.projection().progress()));
        }
        if(distance>limit || distance<=0 || distance+config.lengthSlackMeters()<sourceLength/config.lengthRatioLimit())return Optional.empty();
        return Optional.of(new Path(distance,ranges,a.emission()+b.emission()+Math.abs(distance-sourceLength)/Math.max(sourceLength,1)));
    }
    private Optional<Middle> shortest(PinnedRouteSearchGraph graph,Edge incoming,Edge terminal,Set<String> allowed,String namespace,double limit,boolean coarse,JsonNode turns) {
        String start=incoming.end(),end=terminal.start();
        if(start.equals(end) && (!coarse || canTurn(incoming,terminal,turns)))return Optional.of(new Middle(0,List.of()));
        State initial=new State(start,incoming.index());
        var queue=new PriorityQueue<QueueState>(Comparator.comparingDouble(QueueState::cost).thenComparing(q->q.state().node()).thenComparingInt(q->q.state().prior()));
        Map<State,Double> costs=new HashMap<>();Map<State,Previous> previous=new HashMap<>();queue.add(new QueueState(0,initial));costs.put(initial,0.0);int expanded=0;
        while(!queue.isEmpty()) {
            var current=queue.remove();State state=current.state();if(current.cost()!=costs.get(state))continue;
            Edge prior=graph.edgeByIndex(state.prior()).orElseThrow();
            if(state.node().equals(end) && (!coarse || canTurn(prior,terminal,turns))) {
                List<Edge> path=new ArrayList<>();State at=state;
                while(!at.equals(initial)) {var p=previous.get(at);path.add(graph.edgeByIndex(p.edge()).orElseThrow());at=p.state();}
                java.util.Collections.reverse(path);return Optional.of(new Middle(current.cost(),List.copyOf(path)));
            }
            if(++expanded>config.maxExpandedNodes())return Optional.empty();
            for(Edge edge:graph.out(state.node())) {
                if(!allowed.contains(edge.id()) || !namespace(edge).equals(namespace) || coarse && !canTurn(prior,edge,turns))continue;
                double cost=current.cost()+length(edge.pointsXY());State next=new State(edge.end(),edge.index());
                if(cost<=limit && cost<costs.getOrDefault(next,Double.POSITIVE_INFINITY)) {
                    costs.put(next,cost);previous.put(next,new Previous(state,edge.index()));queue.add(new QueueState(cost,next));
                }
            }
        }
        return Optional.empty();
    }
    private static int comparePaths(Path a,Path b) {
        int c=Double.compare(a.score(),b.score());if(c!=0)return c;
        for(int i=0;i<Math.min(a.ranges().size(),b.ranges().size());i++) {
            var x=a.ranges().get(i);var y=b.ranges().get(i);c=Integer.compare(x.edge().index(),y.edge().index());if(c!=0)return c;
            c=Double.compare(x.start(),y.start());if(c!=0)return c;c=Double.compare(x.end(),y.end());if(c!=0)return c;
        }
        return Integer.compare(a.ranges().size(),b.ranges().size());
    }
    private static String namespace(Edge edge) {return edge.source().equals("ITS")?"ITS":edge.nativeFeature().namespace();}
    private static String nativeKind(Edge edge) {
        var n=edge.nativeFeature();if(n==null || !n.topologyValid() || !"A2_LINK".equals(n.sourceLayer()) || !"2020".equals(n.modelVersion()) || !"2020".equals(attr(edge,"Version")))return null;
        return switch(attr(edge,"LinkType")) {case "4"->"BUS_LANE";case "1"->"INTERSECTION_PATH";default->null;};
    }
    private static boolean busEdge(Edge edge) {return "0".equals(attr(edge,"ROAD_USE")) && Set.of("0","1","2","4","5").contains(attr(edge,"REST_VEH"));}
    private static String attr(Edge edge,String name) {Object value=edge.attrs().get(name);return value==null?"":value.toString();}
    private static String referenceId(Edge edge) {String id=attr(edge,"ITSLinkID");return id.isBlank()?"":"ITS:"+id;}
    private static boolean canTurn(Edge a,Edge b,JsonNode rows) {
        if(!a.end().equals(b.start()))return false;List<JsonNode> exact=new ArrayList<>(),related=new ArrayList<>();
        for(var row:rows) {var turn=row.path("turn");if(!attr(a,"LINK_ID").equals(turn.path("ST_LINK").asString()))continue;
            if(!"DIRECTED_INCIDENT".equals(row.path("bindingStatus").asString()))related.add(row);
            if(attr(b,"LINK_ID").equals(turn.path("ED_LINK").asString()) && attr(a,"T_NODE").equals(turn.path("NODE_ID").asString()))exact.add(row);
        }
        for(var row:java.util.stream.Stream.concat(exact.stream(),related.stream()).toList())if("002".equals(row.path("turn").path("TURN_TYPE").asString()))return true;
        for(var row:exact)if(!"0".equals(row.path("turn").path("TURN_OPER").asString()) || !"DIRECTED_INCIDENT".equals(row.path("bindingStatus").asString()))return true;
        return exact.stream().noneMatch(r->Set.of("003","101","102","103").contains(r.path("turn").path("TURN_TYPE").asString()));
    }
    private static Set<String> retrievalSeeds(JsonNode associations,List<String> coarseIds) {
        Set<String> seeds=new HashSet<>();
        for(var association:associations) {
            require(!association.path("traversabilityBridge").asBoolean(true) && !association.path("modelAdoptionApproved").asBoolean(true)
                && association.path("nodeAliases").isArray() && association.path("nodeAliases").isEmpty(),"ASSOCIATION_MUST_NOT_AUTHORIZE_GRAPH_BRIDGES");
            Map<String,String> currentToFamily=new HashMap<>();
            for(var family:association.path("families"))for(var member:family.path("currentRoadIds"))
                require(currentToFamily.put(member.asString(),family.path("historicalRoadId").asString())==null,"AMBIGUOUS_COURSE_FAMILY");
            Set<String> wanted=new HashSet<>();for(String id:coarseIds) {String family=currentToFamily.get(id.startsWith("ITS:")?id.substring(4):id);if(family!=null)wanted.add(family);}
            for(var feature:association.path("nativeFeatures"))if(wanted.contains(feature.path("declaredRoadId").asString()))seeds.add(feature.path("nativeFeatureId").asString());
        }
        return seeds;
    }
    private Fragment buildFragment(RouteGeometry source,ProviderSourceBinding.Binding binding,Path path,RouteGeometry.Stop from,RouteGeometry.Stop to,int start,int end,PinnedRouteSearchGraph graph) {
        List<Edge> edges=path.ranges().stream().map(Range::edge).toList();Crs nativeCrs=edges.getFirst().nativeFeature().crs();List<NativeXYZ> full=new ArrayList<>();List<Double> lengths=new ArrayList<>();
        for(Edge edge:edges) {
            var n=edge.nativeFeature();require(n!=null && n.crs().equals(nativeCrs),"MIXED_NATIVE_CRS");
            if(!full.isEmpty())require(full.getLast().equals(n.xyz().getFirst()),"NATIVE_GEOMETRY_JOIN_GAP");
            full.addAll(full.isEmpty()?n.xyz():n.xyz().subList(1,n.xyz().size()));lengths.add(length(xy(n.xyz())));
        }
        var before=SourceSearchGeometry.project(xy(edges.getFirst().nativeFeature().xyz()),graph.sourcePoint(nativeCrs,start,source.points().get(start)),true);
        var after=SourceSearchGeometry.project(xy(edges.getLast().nativeFeature().xyz()),graph.sourcePoint(nativeCrs,end,source.points().get(end)),true);
        require(Math.max(before.distance(),after.distance())<=config.radiusMeters(),"RAW_ENDPOINT_OUTSIDE_SELECTED_NATIVE_CORRIDOR");
        double lo=before.progress(),hi=lengths.subList(0,lengths.size()-1).stream().mapToDouble(Double::doubleValue).sum()+after.progress();
        require(lo<hi && lo<lengths.getFirst() && after.progress()>0,"SOURCE_SCOPE_REVERSES_OR_MISSES_NATIVE_OCCURRENCE");
        List<NativeXYZ> clipped=clip(full,lo,hi);var nativeXY=xy(clipped);
        var points=clipped.stream().map(p->NativeReferenceProjection.toWgs84(nativeCrs,p.xy())).toList();
        List<SourceTrace> trace=new ArrayList<>();double last=-1;
        for(int i=start;i<=end;i++) {
            Integer role=binding.pointRoles().get(i);require(role!=null && role>=1 && role<=3,"SOURCE_POINT_ROLE_UNRESOLVED");
            var p=SourceSearchGeometry.project(nativeXY,graph.sourcePoint(nativeCrs,i,source.points().get(i)),true);boolean constrained=role==1 || role==3;
            if(constrained) {require(p.distance()<=config.radiusMeters() && p.progress()+.001>=last,"SOURCE_COURSE_NOT_PRESERVED");last=p.progress();}
            trace.add(new SourceTrace(i,role.toString(),p.segmentIndex(),p.fraction(),List.of(p.point().x(),p.point().y()),p.distance(),p.progress(),constrained));
        }
        List<Visit> visits=new ArrayList<>();double previous=-1;
        for(var stop:List.of(from,to)) {
            XY q=graph.sourceVisit(nativeCrs,stop);var connected=SourceSearchGeometry.project(nativeXY,q,true);var unclipped=SourceSearchGeometry.project(xy(full),q,true);
            require(connected.progress()>=previous && connected.distance()<=config.radiusMeters(),"STOP_CONNECTION_NOT_SUPPORTED_BY_SCOPE");previous=connected.progress();
            int index=connected.segmentIndex();double fraction=connected.fraction();var a=points.get(index);var b=points.get(index+1);
            var point=new RouteDisplayInput.Point(a.x()+(b.x()-a.x())*fraction,a.y()+(b.y()-a.y())*fraction);
            double absolute=lo+connected.progress(),offset=0;int occurrence=0;
            for(;occurrence<lengths.size()-1;occurrence++) {if(absolute<=offset+lengths.get(occurrence))break;offset+=lengths.get(occurrence);}
            double clamp=Math.abs(unclipped.progress()-absolute);
            visits.add(new Visit(stop.stationId(),stop.stopSequence(),stop.direction(),stop.point(),occurrence,new Connection(point,index,fraction),
                connected.progress(),unclipped.progress(),clamp>.001,clamp,unclipped.progress()<lo?"START":unclipped.progress()>hi?"END":null,connected.distance()));
        }
        return new Fragment(new SourceWindow(start,end),new SourceRange(new Position(start,0),new Position(end-1,1)),edges.stream().map(Edge::id).toList(),
            new NativeRange(lo,hi),"EPSG:"+nativeCrs.code(),clipped,points,visits,
            Map.of("start",boundary(before),"end",boundary(after)),trace);
    }
    private static BoundaryProjection boundary(Projection p) {return new BoundaryProjection(p.segmentIndex(),p.fraction(),List.of(p.point().x(),p.point().y()),p.distance(),p.progress());}
    private static XY project(Crs crs,RouteGeometry.Point point) {var p=NativeReferenceProjection.fromWgs84(crs,point);return new XY(p.x(),p.y());}
    private static List<XY> xy(List<NativeXYZ> points) {return points.stream().map(p->new XY(p.x(),p.y())).toList();}
    private static List<NativeXYZ> clip(List<NativeXYZ> full,double start,double end) {
        List<NativeXYZ> result=new ArrayList<>();double progress=0;
        for(int i=0;i<full.size()-1;i++) {var a=full.get(i);var b=full.get(i+1);double size=Math.hypot(b.x()-a.x(),b.y()-a.y());require(size>0,"ZERO_NATIVE_SEGMENT_UNSUPPORTED");
            double next=progress+size;
            if(next>=start && progress<=end) {double lo=Math.max(0,(start-progress)/size),hi=Math.min(1,(end-progress)/size);
                if(lo<=hi) {var first=lo==0?a:lo==1?b:a.at(b,lo);var last=hi==0?a:hi==1?b:a.at(b,hi);
                    if(result.isEmpty())result.add(first);if(!result.getLast().equals(last))result.add(last);}}
            progress=next;if(progress>end)break;
        }
        require(result.size()>=2,"EMPTY_NATIVE_FRAGMENT");return List.copyOf(result);
    }
}
