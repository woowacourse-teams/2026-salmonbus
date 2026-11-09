package com.gustler.backend.routegeometry.engine.api.accuracy;

import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/** Server-side evidence ports, never deserialized from candidate JSON or evaluation ground-truth corpora. */
public final class AccuracyEvidence {
    private AccuracyEvidence() {}
    public enum FeatureKind { ROAD, LANE }
    public record Basis(String sourceId,String contentSha256,String recordId) {
        public Basis {
            if(sourceId==null || sourceId.isBlank() || recordId==null || recordId.isBlank()
                    || contentSha256==null || !contentSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Evidence basis needs source, exact hash, and original record identity");
        }
    }
    /** This value is provided by a source-aware catalog; a candidate edge label never sets its kind or CRS. */
    public record NativeFeature(String id,String namespace,String fromNode,String toNode,FeatureKind kind,
            NativeReferenceProjection.Crs crs,List<NativeReferenceProjection.NativePoint> vertices,List<Basis> basis) {
        public NativeFeature {
            Objects.requireNonNull(id);Objects.requireNonNull(namespace);Objects.requireNonNull(fromNode);Objects.requireNonNull(toNode);
            Objects.requireNonNull(kind);Objects.requireNonNull(crs);vertices=List.copyOf(vertices);basis=List.copyOf(basis);
            if(namespace.isBlank() || !id.startsWith(namespace+":") || !fromNode.startsWith(namespace+":") || !toNode.startsWith(namespace+":") || vertices.size()<2 || basis.isEmpty())
                throw new IllegalArgumentException("Native catalog needs pinned geometry and topology records");
        }
    }
    public record NativeConnection(String fromNode,String toNode,List<Basis> basis) {
        public NativeConnection {
            Objects.requireNonNull(fromNode);Objects.requireNonNull(toNode);basis=List.copyOf(basis);
            if(basis.isEmpty()) throw new IllegalArgumentException("Cross-source connection needs original correspondence evidence");
        }
    }
    public interface NativeCatalog {
        String bundleDigest();
        Optional<NativeFeature> find(String edgeId);
        default Optional<NativeConnection> connection(String fromNode,String toNode) { return Optional.empty(); }
    }
    public enum Decision { SUPPORTED, FORBIDDEN, UNKNOWN }
    public record Check(Decision decision,List<Basis> basis,String reason) {
        public Check {
            Objects.requireNonNull(decision);basis=List.copyOf(basis);
            if(reason==null || !reason.matches("[A-Z0-9_:.-]{1,128}"))
                throw new IllegalArgumentException("Evidence reason must be a bounded machine code, not source/error text");
        }
        public static Check unknown(String reason) { return new Check(Decision.UNKNOWN,List.of(),reason); }
    }
    public record OperatingContext(String vehicleClass,Instant evaluatedAt,Integer heightCm,Long weightKg) {
        public OperatingContext(String vehicleClass,Instant evaluatedAt) { this(vehicleClass,evaluatedAt,null,null); }
        public OperatingContext {
            if(!"BUS".equals(vehicleClass)) throw new IllegalArgumentException("Only explicit BUS context is supported");
            Objects.requireNonNull(evaluatedAt);
            // Representation sanity bounds, not permission limits or inferred dimensions for a BUS label.
            if(heightCm!=null && (heightCm<=0 || heightCm>10_000)
                    || weightKg!=null && (weightKg<=0 || weightKg>1_000_000))
                throw new IllegalArgumentException("Vehicle dimensions need positive bounded centimetres/kilograms");
        }
    }
    /**
     * Implementations must recompute decisions from pinned public-provider/native/operating records.
     * Returning labels copied from a model packet, an allowedPaths evaluation answer, or a caller is not an implementation.
     * SUPPORTED without record bases is rejected by the verifier. Absence of a known prohibition is UNKNOWN.
     * Compare original restriction units with heightCm/weightKg explicitly. Missing actual dimensions leave
     * applicable conditional restrictions UNKNOWN; the BUS label alone never satisfies them.
     */
    public interface Authority {
        Check association(RouteGeometry source,AccuracyCandidate.TracePart part,NativeFeature feature);
        Check operation(AccuracyCandidate.TracePart part,NativeFeature feature,OperatingContext context);
        Check entryExit(AccuracyCandidate.TracePart before,NativeFeature from,
                        AccuracyCandidate.TracePart after,NativeFeature to,OperatingContext context);
        /** Structural projection, a clip-end clamp or a barycentric point on a line is not visit evidence.
         * SUPPORTED needs source-aware road visit/occurrence evidence for this exact represented scope. */
        Check visit(RouteGeometry.Stop stop,Connection connection,RouteGeometry.Point displayedPoint,
                    AccuracyCandidate.TracePart part,NativeFeature feature,OperatingContext context);
        /** Lane/platform support is independent of arithmetic connection validity. A whole-path visit outside
         * the represented scope cannot be certified by clamping it to the fragment's endpoint; return UNKNOWN. */
        default Check laneVisit(RouteGeometry.Stop stop,Connection connection,AccuracyCandidate.TracePart part,
                                NativeFeature feature,OperatingContext context) {
            return Check.unknown("LANE_VISIT_UNRESOLVED");
        }
    }
    public static Authority unresolved() {
        return new Authority() {
            public Check association(RouteGeometry s,AccuracyCandidate.TracePart p,NativeFeature f) { return Check.unknown("ASSOCIATION_UNRESOLVED"); }
            public Check operation(AccuracyCandidate.TracePart p,NativeFeature f,OperatingContext c) { return Check.unknown("OPERATION_UNRESOLVED"); }
            public Check entryExit(AccuracyCandidate.TracePart p,NativeFeature f,AccuracyCandidate.TracePart q,NativeFeature g,OperatingContext c) { return Check.unknown("ENTRY_EXIT_UNRESOLVED"); }
            public Check visit(RouteGeometry.Stop s,Connection c,RouteGeometry.Point d,AccuracyCandidate.TracePart p,NativeFeature f,OperatingContext x) { return Check.unknown("VISIT_UNRESOLVED"); }
        };
    }
    /** Server adoption is a separate gate, binding the exact model code/configuration AND reference bundle epoch. */
    public interface AdoptionPolicy {
        boolean adopted(AccuracyCandidate.ModelIdentity model,String referenceBundleDigest);
        static AdoptionPolicy none() { return (model,reference) -> false; }
    }
}
