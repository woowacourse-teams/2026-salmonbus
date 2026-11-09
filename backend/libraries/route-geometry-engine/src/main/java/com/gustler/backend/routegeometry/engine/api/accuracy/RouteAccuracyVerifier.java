package com.gustler.backend.routegeometry.engine.api.accuracy;

import com.gustler.backend.routegeometry.engine.api.*;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.engine.api.accuracy.AccuracyCandidate.*;
import com.gustler.backend.routegeometry.engine.api.accuracy.AccuracyEvidence.*;
import java.util.*;

/** Recomputes complete reference coverage. A projected curve alone does not prove route assignment or permission. */
public final class RouteAccuracyVerifier {
    public static final String REVISION="complete-reference-coverage-v1";
    /** Numerical projection/serialization precision only (~1mm); this is NOT a road/lane accuracy tolerance. */
    public static final double COORDINATE_EPSILON_DEGREES=1e-8;
    private static final double POSITION_EPSILON=1e-9;
    public enum Grade { UNEVALUATED, ROAD_VERIFIED, LANE_VERIFIED }
    /** REJECTED denotes a contradicted candidate contract or known prohibition, not absence of roads in the world. */
    public enum Disposition { VERIFIED, UNVERIFIED, REJECTED }
    private static final Set<String> REJECTION_CODES=Set.of(
        "INVALID_SOURCE","SOURCE_INPUT_MISMATCH","SOURCE_MANIFEST_MISMATCH","SOURCE_MANIFEST_INVALID",
        "REFERENCE_EPOCH_MISMATCH","REFERENCE_IDENTITY_INVALID",
        "MODEL_IDENTITY_INVALID","ROLE_FACTS_CHANGED","ROLE_FACTS_MALFORMED","INVALID_DIRECTION",
        "DIRECTION_ORDER_CHANGED","OCCURRENCE_ORDER_INVALID","SOURCE_SCOPE_OUT_OF_RANGE","SOURCE_SCOPE_REVERSED",
        "SOURCE_SCOPE_OVERLAP","OUTPUT_RANGE_INVALID","OUTPUT_COVERAGE_OVERLAP","NATIVE_SAMPLE_COUNT_MISMATCH",
        "NATIVE_REFERENCE_ID_MISMATCH","NATIVE_PROGRESS_NOT_FORWARD","NATIVE_PROJECTION_OUTPUT_MISMATCH",
        "NATIVE_VERTEX_SKIPPED_CHORD","INVALID_REFERENCE_PART","NATIVE_TRANSITION_RANGE_INVALID",
        "REFERENCE_TRANSITION_GEOMETRY_GAP","DUPLICATE_SOURCE_VISIT","VISIT_SEGMENT_INVALID",
        "VISIT_CONNECTION_NOT_ON_OUTPUT","VISIT_ORDER_REVERSED","VISIT_EVIDENCE_INVALID","UNKNOWN_OUTPUT_VISIT",
        "ASSOCIATION_FORBIDDEN","OPERATION_FORBIDDEN","TRANSITION_FORBIDDEN","ENTRY_BOUNDARY_FORBIDDEN",
        "EXIT_BOUNDARY_FORBIDDEN","VISIT_FORBIDDEN","LANE_VISIT_FORBIDDEN");
    public record Snapshot(String verifierRevision,Grade grade,String inputDigest,String geometryDigest,
            String sourceManifestId,ModelIdentity model,String referenceBundleDigest,OperatingContext context,int requiredOutputSegments,
            int coveredOutputSegments,int requiredVisits,int coveredVisits,int nativeOccurrences,
            List<String> issues,List<String> laneIssues,List<Basis> evidence,String semanticFactsDigest,Disposition disposition) {
        public Snapshot(String verifierRevision,Grade grade,String inputDigest,String geometryDigest,
                String sourceManifestId,ModelIdentity model,String referenceBundleDigest,OperatingContext context,
                int requiredOutputSegments,int coveredOutputSegments,int requiredVisits,int coveredVisits,int nativeOccurrences,
                List<String> issues,List<String> laneIssues,List<Basis> evidence,String semanticFactsDigest) {
            this(verifierRevision,grade,inputDigest,geometryDigest,sourceManifestId,model,referenceBundleDigest,context,
                requiredOutputSegments,coveredOutputSegments,requiredVisits,coveredVisits,nativeOccurrences,issues,laneIssues,evidence,
                semanticFactsDigest,Disposition.UNVERIFIED);
        }
        public Snapshot(String verifierRevision,Grade grade,String inputDigest,String geometryDigest,
                String sourceManifestId,ModelIdentity model,String referenceBundleDigest,OperatingContext context,
                int requiredOutputSegments,int coveredOutputSegments,int requiredVisits,int coveredVisits,int nativeOccurrences,
                List<String> issues,List<String> laneIssues,List<Basis> evidence) {
            this(verifierRevision,grade,inputDigest,geometryDigest,sourceManifestId,model,referenceBundleDigest,context,
                requiredOutputSegments,coveredOutputSegments,requiredVisits,coveredVisits,nativeOccurrences,issues,laneIssues,evidence,null);
        }
        public Snapshot {
            issues=List.copyOf(issues);laneIssues=List.copyOf(laneIssues);evidence=List.copyOf(evidence);
            // Old immutable baseline payloads did not carry this field; absence is never an approval.
            disposition=Objects.requireNonNullElse(disposition,Disposition.UNVERIFIED);
        }
    }
    /** Only this verifier can issue a Report. Serialized Snapshot data is not an executable authority. */
    public static final class Report {
        private final Snapshot snapshot;
        private Report(Snapshot snapshot) { this.snapshot=snapshot; }
        public Snapshot snapshot() { return snapshot; }
        public Grade grade() { return snapshot.grade(); }
        public Disposition disposition() { return snapshot.disposition(); }
        /** Approval for this report's exact subject/context only; callers must also bind the current subject and policy. */
        public boolean referencePublishable() {
            return disposition()==Disposition.VERIFIED && grade()!=Grade.UNEVALUATED;
        }
        /** Identity check only. It can be true for a rejected or unverified result and never authorizes serving. */
        public boolean binds(RouteGeometry source,RouteDisplayGeometry output,String consumedManifestId) {
            return (snapshot.grade()==Grade.UNEVALUATED || snapshot.semanticFactsDigest()==null)
                && payloadBinds(source,output,consumedManifestId);
        }
        private boolean payloadBinds(RouteGeometry source,RouteDisplayGeometry output,String consumedManifestId) {
            try {
                return (snapshot.grade()==Grade.UNEVALUATED || displayable(output))
                    && snapshot.inputDigest().equals(RouteDisplayGeneration.input(source).digest())
                    && snapshot.geometryDigest().equals(GeometryPayloadFingerprint.of(output))
                    && Objects.equals(snapshot.sourceManifestId(),consumedManifestId);
            } catch(RuntimeException invalid) { return false; }
        }
        public boolean binds(RouteGeometry source,RouteDisplayGeometry output,String consumedManifestId,AdoptionPolicy policy) {
            try {
                return binds(source,output,consumedManifestId)
                    && (snapshot.grade()==Grade.UNEVALUATED || policy.adopted(snapshot.model(),snapshot.referenceBundleDigest()));
            } catch(RuntimeException unavailable) { return false; }
        }
        /** The current role digest must come from the server's raw-source fact reader, never candidate JSON. */
        public boolean binds(RouteGeometry source,RouteDisplayGeometry output,String consumedManifestId,
                OperatingContext context,AdoptionPolicy policy,String currentFactsDigest) {
            try {
                return snapshot.context().equals(context)
                    && semanticFactsMatch(snapshot.semanticFactsDigest(),currentFactsDigest)
                    && payloadBinds(source,output,consumedManifestId)
                    && (snapshot.grade()==Grade.UNEVALUATED || policy.adopted(snapshot.model(),snapshot.referenceBundleDigest()));
            } catch(RuntimeException unavailable) { return false; }
        }
        /** No claim may silently transfer to a different vehicle/time context. */
        public boolean binds(RouteGeometry source,RouteDisplayGeometry output,String consumedManifestId,
                OperatingContext context,AdoptionPolicy policy) {
            return snapshot.context().equals(context) && binds(source,output,consumedManifestId,policy);
        }
    }
    private static boolean displayable(RouteDisplayGeometry output) {
        return output.paths()!=null && output.source()==RouteDisplayGeometry.Source.AUTOMATIC_FALLBACK
            && output.quality()!=null && output.quality().status()!=null
            && output.quality().status()!=RouteDisplayGeometry.QualityStatus.WITHHELD
            && output.quality().status()!=RouteDisplayGeometry.QualityStatus.SOURCE_ONLY;
    }
    /** Baseline engines have no reference model proof. Do not fabricate model/code digests for them. */
    public static Report unassessed(RouteGeometry source,RouteDisplayGeometry output,OperatingContext context) {
        Objects.requireNonNull(source);Objects.requireNonNull(output);Objects.requireNonNull(context);
        String digest=RouteDisplayGeneration.input(source).digest();
        int segments=output.paths()==null?0:output.paths().up().points().size()+output.paths().down().points().size()-2;
        return new Report(new Snapshot(REVISION,Grade.UNEVALUATED,digest,GeometryPayloadFingerprint.of(output),
            source.shape()==null?null:source.shape().sourceManifestId(),null,null,context,segments,0,
            source.busStops().size(),0,0,List.of("NO_REFERENCE_MODEL_INPUT"),List.of(),List.of()));
    }

    private record CheckedPart(TracePart trace,NativeFeature feature,double first,double last) {}
    private final NativeCatalog catalog;
    private final Authority authority;
    private final AdoptionPolicy adoption;
    public RouteAccuracyVerifier(NativeCatalog catalog,Authority authority,AdoptionPolicy adoption) {
        this.catalog=Objects.requireNonNull(catalog);this.authority=Objects.requireNonNull(authority);
        this.adoption=Objects.requireNonNull(adoption);
    }

    public Report verify(RouteGeometry source,RouteDisplayGeometry output,AccuracyCandidate candidate,OperatingContext context) {
        return verify(source,output,candidate,context,null);
    }
    /** Bind consumed center/bus role facts separately from the unchanged manual RouteDisplayInput digest.
     * serverCurrentSemanticFactsDigest is supplied by a source-aware server reader, not the model packet. */
    public Report verify(RouteGeometry source,RouteDisplayGeometry output,AccuracyCandidate candidate,OperatingContext context,
            String serverCurrentSemanticFactsDigest) {
        Objects.requireNonNull(source);Objects.requireNonNull(output);Objects.requireNonNull(candidate);Objects.requireNonNull(context);
        var issues=new LinkedHashSet<String>();var laneIssues=new LinkedHashSet<String>();var bases=new LinkedHashSet<Basis>();
        if(!semanticFactsMatch(candidate.semanticFactsDigest(),serverCurrentSemanticFactsDigest)) {
            issues.add("ROLE_FACTS_UNBOUND_OR_CHANGED");
            if(candidate.semanticFactsDigest()!=null && !isHash(candidate.semanticFactsDigest())
                    || serverCurrentSemanticFactsDigest!=null && !isHash(serverCurrentSemanticFactsDigest))
                issues.add("ROLE_FACTS_MALFORMED");
            else if(candidate.semanticFactsDigest()!=null && serverCurrentSemanticFactsDigest!=null)
                issues.add("ROLE_FACTS_CHANGED");
        }
        String digest;
        try { digest=RouteDisplayGeneration.input(source).digest(); }
        catch(RuntimeException invalid) { digest="INVALID_SOURCE";issues.add("INVALID_SOURCE"); }
        if(!digest.equals(candidate.inputDigest())) issues.add("SOURCE_INPUT_MISMATCH");
        String manifest=source.shape()==null?null:source.shape().sourceManifestId();
        if(!isHash(manifest)) issues.add("INPUT_PROVENANCE_MISSING");
        if(!Objects.equals(manifest,candidate.sourceManifestId())) {
            if(manifest==null || candidate.sourceManifestId()==null) issues.add("SOURCE_MANIFEST_UNBOUND");
            else if(!isHash(manifest) || !isHash(candidate.sourceManifestId())) issues.add("SOURCE_MANIFEST_INVALID");
            else issues.add("SOURCE_MANIFEST_MISMATCH");
        }
        try {
            String serverReference=catalog.bundleDigest();
            if(candidate.referenceBundleDigest()==null || serverReference==null) issues.add("REFERENCE_EPOCH_UNBOUND");
            else if(!isHash(candidate.referenceBundleDigest()) || !isHash(serverReference)) issues.add("REFERENCE_IDENTITY_INVALID");
            else if(!candidate.referenceBundleDigest().equals(serverReference)) issues.add("REFERENCE_EPOCH_MISMATCH");
        } catch(RuntimeException unavailable) { issues.add("NATIVE_CATALOG_UNAVAILABLE"); }
        if(candidate.model().modelId().isBlank() || !isHash(candidate.model().codeSha256()) || !isHash(candidate.model().configSha256()))
            issues.add("MODEL_IDENTITY_INVALID");
        try { if(!adoption.adopted(candidate.model(),candidate.referenceBundleDigest())) issues.add("MODEL_NOT_ADOPTED"); }
        catch(RuntimeException unavailable) { issues.add("MODEL_ADOPTION_UNAVAILABLE"); }
        if(output.paths()==null) issues.add("NO_EMITTED_PATHS");
        if(output.quality()==null || output.quality().status()==null || output.quality().status()==RouteDisplayGeometry.QualityStatus.WITHHELD)
            issues.add("OUTPUT_QUALITY_NOT_DISPLAYABLE");
        if(output.quality()!=null && output.quality().status()==RouteDisplayGeometry.QualityStatus.SOURCE_ONLY)
            issues.add("SOURCE_ONLY_NOT_MODEL_VERIFIED");
        if(output.source()!=RouteDisplayGeometry.Source.AUTOMATIC_FALLBACK) issues.add("NO_AUTOMATIC_MODEL_BINDING");
        var expected=new HashMap<String,Integer>();expected.put("UP",0);expected.put("DOWN",0);
        int required=output.paths()==null?0:output.paths().up().points().size()+output.paths().down().points().size()-2;
        int covered=0;double sourceCursor=0;boolean downSeen=false;var occurrences=new HashSet<String>();
        var checked=new ArrayList<CheckedPart>();var previousOccurrences=new HashMap<String,Integer>();
        for(var part:candidate.parts()) {
            try {
                if(!List.of("UP","DOWN").contains(part.direction())) { issues.add("INVALID_DIRECTION");continue; }
                if("DOWN".equals(part.direction())) downSeen=true;
                else if(downSeen) issues.add("DIRECTION_ORDER_CHANGED");
                if(!occurrences.add(part.direction()+":"+part.occurrence()) || part.occurrence()<0
                        || part.occurrence()!=previousOccurrences.getOrDefault(part.direction(),-1)+1)
                    issues.add("OCCURRENCE_ORDER_INVALID");
                previousOccurrences.put(part.direction(),part.occurrence());
                if(part.origin()!=Origin.REFERENCE) issues.add("UNVERIFIED_OR_FALLBACK_PART");
                var raw=part.sourceSpan();
                if(raw.start().segmentIndex()>=source.points().size()-1 || raw.end().segmentIndex()>=source.points().size()-1) {
                    issues.add("SOURCE_SCOPE_GAP_OVERLAP_OR_ORDER");issues.add("SOURCE_SCOPE_OUT_OF_RANGE");
                }
                if(raw.end().ordinal()<=raw.start().ordinal()) {
                    issues.add("SOURCE_SCOPE_GAP_OVERLAP_OR_ORDER");issues.add("SOURCE_SCOPE_REVERSED");
                }
                if(!equal(raw.start().ordinal(),sourceCursor)) {
                    issues.add("SOURCE_SCOPE_GAP_OVERLAP_OR_ORDER");
                    issues.add(raw.start().ordinal()<sourceCursor?"SOURCE_SCOPE_OVERLAP":"SOURCE_SCOPE_GAP");
                }
                sourceCursor=raw.end().ordinal();
                if(output.paths()==null) continue;
                var path=points(output,part.direction());
                if(part.startOutputIndex()!=expected.get(part.direction()) || part.endOutputIndex()<=part.startOutputIndex()
                        || part.startOutputIndex()<0 || part.endOutputIndex()>=path.size()) {
                    issues.add("OUTPUT_COVERAGE_GAP_OVERLAP");
                    if(part.endOutputIndex()<=part.startOutputIndex() || part.startOutputIndex()<0 || part.endOutputIndex()>=path.size())
                        issues.add("OUTPUT_RANGE_INVALID");
                    if(part.startOutputIndex()<expected.get(part.direction())) issues.add("OUTPUT_COVERAGE_OVERLAP");
                    if(part.startOutputIndex()>expected.get(part.direction())) issues.add("OUTPUT_COVERAGE_GAP");
                    continue;
                }
                expected.put(part.direction(),part.endOutputIndex());
                if(part.nativeSamples().size()!=part.endOutputIndex()-part.startOutputIndex()+1) {
                    issues.add("NATIVE_SAMPLE_COUNT_MISMATCH");continue;
                }
                Optional<NativeFeature> found;
                try { found=Objects.requireNonNull(catalog.find(part.edgeId())); }
                catch(RuntimeException unavailable) { issues.add("NATIVE_CATALOG_UNAVAILABLE");continue; }
                if(found.isEmpty()) { issues.add("NATIVE_REFERENCE_MISSING");continue; }
                var edge=found.get();
                if(!edge.id().equals(part.edgeId())) { issues.add("NATIVE_REFERENCE_ID_MISMATCH");continue; }
                if(edge.kind()!=FeatureKind.LANE) laneIssues.add("NON_LANE_REFERENCE");
                bases.addAll(edge.basis());
                var samples=part.nativeSamples();double nativePrevious=-1;
                var ordinals=new ArrayList<Double>();
                for(int i=0;i<samples.size();i++) {
                    var position=samples.get(i);
                    double metres=NativeReferenceProjection.nativeChainage(edge.crs(),edge.vertices(),position.segmentIndex(),position.fraction());
                    if(metres<=nativePrevious && i>0) issues.add("NATIVE_PROGRESS_NOT_FORWARD");
                    nativePrevious=metres;ordinals.add(position.ordinal());
                    var actual=NativeReferenceProjection.toWgs84(edge.crs(),NativeReferenceProjection.nativePointAt(edge.vertices(),position.segmentIndex(),position.fraction()));
                    if(!samePoint(actual,path.get(part.startOutputIndex()+i))) issues.add("NATIVE_PROJECTION_OUTPUT_MISMATCH");
                }
                double first=samples.getFirst().ordinal(),last=samples.getLast().ordinal();
                for(int vertex=(int)Math.floor(first)+1;vertex<last-POSITION_EPSILON;vertex++) {
                    final int boundary=vertex;
                    if(ordinals.stream().noneMatch(x -> equal(x,boundary))) issues.add("NATIVE_VERTEX_SKIPPED_CHORD");
                }
                var current=new CheckedPart(part,edge,first,last);
                if(!checked.isEmpty()) checkTransition(checked.getLast(),current,context,issues,bases);
                checked.add(current);covered+=part.endOutputIndex()-part.startOutputIndex();
                checkAuthority(() -> authority.association(source,part,edge),"ASSOCIATION",issues,bases);
                checkAuthority(() -> authority.operation(part,edge,context),"OPERATION",issues,bases);
            } catch(RuntimeException invalid) { issues.add("INVALID_REFERENCE_PART"); }
        }
        if(output.paths()!=null && (expected.get("UP")!=output.paths().up().points().size()-1
                || expected.get("DOWN")!=output.paths().down().points().size()-1)) issues.add("INCOMPLETE_OUTPUT_COVERAGE");
        if(candidate.parts().isEmpty() || !equal(sourceCursor,source.points().size()-1)) issues.add("INCOMPLETE_WHOLE_SOURCE_SCOPE");
        if(covered!=required || required==0) issues.add("INCOMPLETE_REFERENCE_COVERAGE");
        // Both external route boundaries require source-aware ingress/egress evidence, even for a single edge.
        if(!checked.isEmpty()) {
            checkAuthority(() -> authority.entryExit(null,null,checked.getFirst().trace(),checked.getFirst().feature(),context),"ENTRY_BOUNDARY",issues,bases);
            checkAuthority(() -> authority.entryExit(checked.getLast().trace(),checked.getLast().feature(),null,null,context),"EXIT_BOUNDARY",issues,bases);
        }
        int visits=checkVisits(source,output,checked,context,issues,laneIssues,bases);
        boolean rejected=issues.stream().anyMatch(REJECTION_CODES::contains);
        Grade grade=!rejected && issues.isEmpty()?(laneIssues.isEmpty()?Grade.LANE_VERIFIED:Grade.ROAD_VERIFIED):Grade.UNEVALUATED;
        Disposition disposition=rejected?Disposition.REJECTED:grade==Grade.UNEVALUATED?Disposition.UNVERIFIED:Disposition.VERIFIED;
        return new Report(new Snapshot(REVISION,grade,digest,GeometryPayloadFingerprint.of(output),manifest,candidate.model(),
            candidate.referenceBundleDigest(),context,required,covered,source.busStops().size(),visits,checked.size(),
            List.copyOf(issues),List.copyOf(laneIssues),List.copyOf(bases),candidate.semanticFactsDigest(),disposition));
    }

    private void checkTransition(CheckedPart before,CheckedPart after,OperatingContext context,Set<String> issues,Set<Basis> bases) {
        boolean continuousSame=before.feature().id().equals(after.feature().id()) && equal(before.last(),after.first());
        if(!continuousSame) {
            if(!equal(before.last(),before.feature().vertices().size()-1) || !equal(after.first(),0)) {
                issues.add("NATIVE_TOPOLOGY_NOT_CONNECTED");issues.add("NATIVE_TRANSITION_RANGE_INVALID");
            }
            if(!before.feature().toNode().equals(after.feature().fromNode())) {
                try {
                    var bridge=Objects.requireNonNull(catalog.connection(before.feature().toNode(),after.feature().fromNode()));
                    if(bridge.isEmpty() || !bridge.get().fromNode().equals(before.feature().toNode())
                            || !bridge.get().toNode().equals(after.feature().fromNode()) || bridge.get().basis().isEmpty())
                        issues.add("NATIVE_TOPOLOGY_NOT_CONNECTED");
                    else bases.addAll(bridge.get().basis());
                } catch(RuntimeException unavailable) { issues.add("NATIVE_CONNECTION_EVIDENCE_UNAVAILABLE"); }
            }
            // Matching IDs alone cannot hide a discontinuous original-node geometry or CRS epoch.
            var a=NativeReferenceProjection.toWgs84(before.feature().crs(),NativeReferenceProjection.nativePointAt(before.feature().vertices(),
                before.trace().nativeSamples().getLast().segmentIndex(),before.trace().nativeSamples().getLast().fraction()));
            var b=NativeReferenceProjection.toWgs84(after.feature().crs(),NativeReferenceProjection.nativePointAt(after.feature().vertices(),
                after.trace().nativeSamples().getFirst().segmentIndex(),after.trace().nativeSamples().getFirst().fraction()));
            if(!samePoint(a,b)) issues.add("REFERENCE_TRANSITION_GEOMETRY_GAP");
        }
        checkAuthority(() -> authority.entryExit(before.trace(),before.feature(),after.trace(),after.feature(),context),"TRANSITION",issues,bases);
    }
    private int checkVisits(RouteGeometry source,RouteDisplayGeometry output,List<CheckedPart> parts,OperatingContext context,
            Set<String> issues,Set<String> laneIssues,Set<Basis> bases) {
        var seen=new HashSet<Integer>();var previous=new HashMap<String,Double>();int count=0;
        for(var stop:source.busStops()) {
            try {
                if(!seen.add(stop.stopSequence())) { issues.add("DUPLICATE_SOURCE_VISIT");continue; }
                if(stop.point()==null) { issues.add("VISIT_SOURCE_COORDINATE_MISSING");continue; }
                if(!List.of("UP","DOWN").contains(stop.direction()) || output.paths()==null) { issues.add("VISIT_DIRECTION_UNVERIFIED");continue; }
                Connection connection=output.connections().get(stop.stopSequence());
                if(connection==null) { issues.add("VISIT_CONNECTION_MISSING");continue; }
                var path=points(output,stop.direction());int segment=connection.segmentIndex();
                if(segment>=path.size()-1) { issues.add("VISIT_SEGMENT_INVALID");continue; }
                var a=path.get(segment);var b=path.get(segment+1);double t=connection.segmentFraction();
                var point=new RouteGeometry.Point(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t);
                if(!samePoint(point,new RouteGeometry.Point(connection.point().x(),connection.point().y()))) {
                    issues.add("VISIT_CONNECTION_NOT_ON_OUTPUT");continue;
                }
                double progress=segment+t;
                if(progress+POSITION_EPSILON<previous.getOrDefault(stop.direction(),-1.0)) issues.add("VISIT_ORDER_REVERSED");
                previous.put(stop.direction(),progress);
                var owner=parts.stream().filter(p -> p.trace().direction().equals(stop.direction())
                    && segment>=p.trace().startOutputIndex() && segment<p.trace().endOutputIndex()).findFirst();
                if(owner.isEmpty()) { issues.add("VISIT_REFERENCE_COVERAGE_MISSING");continue; }
                var part=owner.get();
                checkAuthority(() -> authority.visit(stop,connection,output.stationPositions().getOrDefault(stop.stopSequence(),stop.point()),
                    part.trace(),part.feature(),context),"VISIT",issues,bases);
                if(part.feature().kind()==FeatureKind.LANE) {
                    try {
                        var lane=authority.laneVisit(stop,connection,part.trace(),part.feature(),context);
                        // A known prohibition cannot be hidden by downgrading its label to ROAD_VERIFIED.
                        check(lane,"LANE_VISIT",lane!=null && lane.decision()==Decision.FORBIDDEN?issues:laneIssues,bases);
                    } catch(RuntimeException unavailable) { laneIssues.add("LANE_VISIT_UNAVAILABLE"); }
                }
                count++;
            } catch(RuntimeException invalid) { issues.add("VISIT_EVIDENCE_INVALID"); }
        }
        if(!seen.containsAll(output.connections().keySet()) || !seen.containsAll(output.stationPositions().keySet())) issues.add("UNKNOWN_OUTPUT_VISIT");
        if(count!=source.busStops().size()) issues.add("INCOMPLETE_VISIT_COVERAGE");
        return count;
    }
    private static List<RouteGeometry.Point> points(RouteDisplayGeometry display,String direction) {
        return direction.equals("UP")?display.paths().up().points():display.paths().down().points();
    }
    private static void checkAuthority(java.util.function.Supplier<Check> source,String scope,Set<String> issues,Set<Basis> bases) {
        try { check(source.get(),scope,issues,bases); }
        catch(RuntimeException unavailable) { issues.add(scope+"_UNAVAILABLE"); }
    }
    private static void check(Check result,String scope,Set<String> issues,Set<Basis> bases) {
        if(result!=null) bases.addAll(result.basis());
        if(result==null || result.decision()!=Decision.SUPPORTED || result.basis().isEmpty()) {
            issues.add(scope+"_"+(result==null?"UNAVAILABLE":result.decision()==Decision.SUPPORTED?"UNBOUND_EVIDENCE":result.decision().name()));
            if(result!=null) issues.add(scope+":"+result.reason());
        }
    }
    private static boolean semanticFactsMatch(String consumed,String current) {
        return consumed==null && current==null || isHash(consumed) && isHash(current) && consumed.equals(current);
    }
    private static boolean isHash(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
    private static boolean equal(double a,double b) { return Math.abs(a-b)<=POSITION_EPSILON; }
    private static boolean samePoint(RouteGeometry.Point a,RouteGeometry.Point b) {
        return Math.abs(a.x()-b.x())<=COORDINATE_EPSILON_DEGREES && Math.abs(a.y()-b.y())<=COORDINATE_EPSILON_DEGREES;
    }
}
