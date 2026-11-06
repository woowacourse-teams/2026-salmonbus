package com.gustler.backend.worker.routegeometry.reference;

import static com.gustler.backend.worker.routegeometry.reference.ReferenceJson.*;
import com.gustler.backend.routegeometry.api.RouteModelFacts;
import com.gustler.backend.routegeometry.engine.api.*;
import com.gustler.backend.routegeometry.engine.api.SourceObservationRouteMatcher.*;
import com.gustler.backend.routegeometry.engine.api.SourceObservationPreparation.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** One server-declared source window, not a whole-route model or served geometry. */
public final class GlobalReferenceMatcherAdapter {
    public static final String REVISION="server-window-global-matcher-adapter-v1";
    public record RawProgress(int originalPointIndex,double globalRawMetres,double scopeRawMetres) {}
    public record Diagnostic(String revision,String status,String reason,String inputDigest,String graphDigest,
            ReferenceFragmentVerifier.SourceWindow sourceWindow,String direction,int graphEdgeCount,
            String bmsPriorStatus,List<RawProgress> rawProgress,SourceObservationPreparation.Prepared prepared,
            SourceObservationRouteMatcher.Result globalResult,String turnEvidenceDocument,
            List<RouteGeometry.Point> sourceFallbackPoints,List<String> notes,
            boolean modelAdopted,boolean published,boolean wholeRouteCoverageVerified,String accuracyGrade) {
        public Diagnostic {rawProgress=List.copyOf(rawProgress);sourceFallbackPoints=List.copyOf(sourceFallbackPoints);notes=List.copyOf(notes);}
    }
    public Diagnostic generate(RouteGeometry source,RouteModelFacts facts,PinnedRouteSearchGraph graph) {
        var root=graph.tree();var scope=root.path("scope");var range=scope.path("sourceWindow");
        var window=new ReferenceFragmentVerifier.SourceWindow(integer(range.path("startPointIndex")),integer(range.path("endPointIndex")));
        String input=RouteDisplayGeneration.input(source).digest(),direction=text(scope,"direction");
        List<RouteGeometry.Point> fallback=window.endPointIndex()<source.points().size()?source.points().subList(window.startPointIndex(),window.endPointIndex()+1):source.points();
        var notes=List.of("SERVER_DECLARED_SOURCE_WINDOW_ONLY","RAW_SOURCE_PROGRESS_AND_CLEANED_SOURCE_AXIS_AND_BACKBONE_DISTANCE_ARE_DISTINCT",
            "NO_OFFICIAL_BMS_PRIOR_IN_THIS_PACKET","REFERENCE_CANDIDATE_IS_NOT_VERIFIED_DISPLAY_GEOMETRY");
        try {
            require(graph.usesCanonicalObservations(),"CANONICAL_SOURCE_OBSERVATIONS_REQUIRED");
            require(source.routeId().equals(text(scope,"routeId")) && input.equals(text(scope,"inputDigest")),"GLOBAL_SOURCE_INPUT_MISMATCH");
            require(Set.of("UP","DOWN").contains(direction),"ONE_DIRECTION_SOURCE_WINDOW_REQUIRED");
            var binding=ProviderSourceBinding.bind(source,facts,graph.providerWeb());
            require(root.has("turnWitnessEvidence"),"ORIGINAL_TURN_WITNESSES_UNAVAILABLE");TurnWitnessEvidence.inputDigests(root);
            var evidence=root.path("turnWitnessEvidence");var turnFacts=turnFacts(evidence);var engineGraph=adaptGraph(graph,turnFacts);
            MetricSpace space=engineGraph.space();List<SourceVertex> vertices=new ArrayList<>();List<RawProgress> rawProgress=new ArrayList<>();
            double global=0,base=0;PinnedRouteSearchGraph.XY previous=null;
            for(int i=0;i<=window.endPointIndex();i++) {
                var p=graph.sourcePoint(graph.engineCrs(),i,source.points().get(i));
                if(previous!=null)global+=Math.hypot(p.x()-previous.x(),p.y()-previous.y());previous=p;
                if(i<window.startPointIndex())continue;if(i==window.startPointIndex())base=global;
                Integer type=binding.pointRoles().get(i);require(type!=null,"BOUND_PROVIDER_POINT_ROLE_REQUIRED");
                Role role=type==2?Role.BOUND_TYPE2_CONTROL:type==1||type==3?Role.REAL_SHAPE:Role.UNKNOWN;
                vertices.add(new SourceVertex(i,new XY(p.x(),p.y()),role));rawProgress.add(new RawProgress(i,global,global-base));
            }
            var axis=SourceObservationPreparation.prepare(space,vertices,List.of());Map<Integer,Double> progress=new HashMap<>();
            for(int i=0;i<axis.sourceAxisVertices().size();i++)progress.put(axis.sourceAxisVertices().get(i).originalPointIndex(),axis.sourceVertexProgressMetres().get(i));
            List<MandatoryVisit> mandatory=new ArrayList<>();Set<Integer> visits=new HashSet<>();
            for(var visit:binding.visits())if(visit.sourcePointIndex()>=window.startPointIndex() && visit.sourcePointIndex()<=window.endPointIndex()) {
                require(direction.equals(visit.direction()),"MIXED_DIRECTION_SOURCE_WINDOW_UNSUPPORTED");
                Double value=progress.get(visit.sourcePointIndex());require(value!=null,"MANDATORY_VISIT_SOURCE_VERTEX_REMOVED");
                mandatory.add(new MandatoryVisit(visit.stationId()+":"+visit.sequence()+":"+visit.direction(),value,null));visits.add(visit.sequence());
            }
            require(visits.contains(integer(scope.path("fromSequence"))) && visits.contains(integer(scope.path("toSequence"))),"GLOBAL_MANDATORY_SCOPE_MISMATCH");
            var prepared=SourceObservationPreparation.prepare(space,vertices,mandatory);
            requireSearchDomain(root,prepared.observations());
            var result=new SourceObservationRouteMatcher(engineGraph).matchPrepared(prepared.observations());
            String status=result.intervals().stream().anyMatch(i->i.kind()==IntervalKind.SOURCE_FALLBACK)?"SOURCE_FALLBACK_DIAGNOSTIC":"MATCHED_DIAGNOSTIC";
            return new Diagnostic(REVISION,status,"GLOBAL_MATCHING_NOT_MODEL_ADOPTION",input,graph.digest(),window,direction,graph.edges().size(),
                "UNKNOWN_NO_PINNED_BMS_SOURCE",rawProgress,prepared,result,new ObjectMapper().writeValueAsString(evidence),fallback,notes,false,false,false,"UNEVALUATED");
        } catch(IllegalArgumentException unavailable) {
            return new Diagnostic(REVISION,"INSUFFICIENT",machine(unavailable.getMessage()),input,graph.digest(),window,direction,graph.edges().size(),
                "UNKNOWN_NO_PINNED_BMS_SOURCE",List.of(),null,null,root.has("turnWitnessEvidence")?new ObjectMapper().writeValueAsString(root.path("turnWitnessEvidence")):null,
                fallback,notes,false,false,false,"UNEVALUATED");
        }
    }
    static Graph adaptGraph(PinnedRouteSearchGraph graph,List<TurnFact> turns) {
        List<DirectedEdge> edges=new ArrayList<>();
        for(var edge:graph.edges()) {
            Source source=Source.valueOf(edge.source());String namespace=source==Source.ITS?"ITS":edge.nativeFeature().namespace();
            edges.add(new DirectedEdge(edge.index(),edge.id(),edge.start(),edge.end(),namespace,source,
                edge.pointsXY().stream().map(p->new XY(p.x(),p.y())).toList(),edge.attrs()));
        }
        return new Graph(new MetricSpace("EPSG:"+graph.engineCrs().code(),true,1),edges,turns);
    }
    private static List<TurnFact> turnFacts(JsonNode evidence) {
        List<TurnFact> facts=new ArrayList<>();
        for(var row:evidence.path("sourceRows")) {
            var turn=row.path("turn");List<LinkWitness> starts=witnesses(row.path("startLinks")),ends=witnesses(row.path("endLinks"));
            require(!starts.isEmpty() && !ends.isEmpty() && !row.path("nodes").isEmpty(),"ORIGINAL_TURN_WITNESSES_UNAVAILABLE");
            for(var node:row.path("nodes"))require(text(node,"NODE_ID").equals(text(turn,"NODE_ID")) && "1".equals(text(node,"TURN_P")),"TURN_NODE_WITNESS_UNRESOLVED");
            facts.add(new TurnFact(integer(row.path("sourceRecordIndex")),"ITS:"+text(turn,"ST_LINK"),"ITS:"+text(turn,"ED_LINK"),
                "ITS:"+text(turn,"NODE_ID"),text(turn,"TURN_TYPE"),text(turn,"TURN_OPER"),text(row,"bindingStatus"),starts,ends));
        }
        return List.copyOf(facts);
    }
    private static List<LinkWitness> witnesses(JsonNode rows) {
        List<LinkWitness> out=new ArrayList<>();
        for(var row:rows)out.add(new LinkWitness("ITS:"+text(row,"LINK_ID"),"ITS:"+text(row,"F_NODE"),"ITS:"+text(row,"T_NODE")));
        return List.copyOf(out);
    }
    private static void requireSearchDomain(JsonNode root,List<Observation> observations) {
        var boxes=root.at("/searchEnvelope/boxes");require(boxes.isArray() && !boxes.isEmpty(),"GLOBAL_SEARCH_ENVELOPE_REQUIRED");
        for(int i=0;i<observations.size();i++) {
            var observation=observations.get(i);double extra=0;
            if(i+1<observations.size())extra=Math.min(2000,Math.max(120,(observations.get(i+1).distanceProgressMetres()-observation.distanceProgressMetres())*4+60));
            double radius=SourceObservationRouteMatcher.CANDIDATE_RADIUS_METRES+extra;boolean covered=false;
            for(var box:boxes)if(box.isArray() && box.size()==4 && number(box.get(0))<=observation.point().x()-radius && number(box.get(1))<=observation.point().y()-radius
                    && number(box.get(2))>=observation.point().x()+radius && number(box.get(3))>=observation.point().y()+radius)covered=true;
            require(covered,"GLOBAL_TRANSITION_DOMAIN_NOT_COVERED_BY_PINNED_SEARCH");
        }
    }
    private static String machine(String reason){return reason!=null && reason.matches("[A-Z0-9_]{1,128}")?reason:"GLOBAL_INPUT_UNSUPPORTED";}
}
