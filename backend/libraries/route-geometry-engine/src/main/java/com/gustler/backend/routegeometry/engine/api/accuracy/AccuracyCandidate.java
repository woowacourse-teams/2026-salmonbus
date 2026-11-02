package com.gustler.backend.routegeometry.engine.api.accuracy;

import java.util.List;
import java.util.Objects;

/** Untrusted model trace. It deliberately has no grade, verified flag, or adoption flag. */
public record AccuracyCandidate(ModelIdentity model, String inputDigest, String sourceManifestId,
        String referenceBundleDigest, List<TracePart> parts, String semanticFactsDigest) {
    public AccuracyCandidate(ModelIdentity model,String inputDigest,String sourceManifestId,
            String referenceBundleDigest,List<TracePart> parts) {
        this(model,inputDigest,sourceManifestId,referenceBundleDigest,parts,null);
    }
    public AccuracyCandidate { Objects.requireNonNull(model); parts=List.copyOf(parts); }
    public record ModelIdentity(String modelId,String codeSha256,String configSha256) {
        public ModelIdentity { Objects.requireNonNull(modelId); Objects.requireNonNull(codeSha256); Objects.requireNonNull(configSha256); }
    }
    public enum Origin { REFERENCE, SOURCE_FALLBACK, UNKNOWN }
    public record Position(int segmentIndex,double fraction) {
        public Position {
            if(segmentIndex<0 || !Double.isFinite(fraction) || fraction<0 || fraction>1)
                throw new IllegalArgumentException("Invalid segment/fraction position");
        }
        public double ordinal() { return segmentIndex+fraction; }
    }
    public record RawSpan(Position start,Position end) {
        public RawSpan { Objects.requireNonNull(start);Objects.requireNonNull(end); }
    }
    /** start/endOutputIndex are vertex indexes; this covers segments [start,end). Samples stay on native segments. */
    public record TracePart(String direction,int startOutputIndex,int endOutputIndex,String edgeId,
            int occurrence,List<Position> nativeSamples,RawSpan sourceSpan,Origin origin) {
        public TracePart {
            nativeSamples=List.copyOf(nativeSamples); Objects.requireNonNull(sourceSpan);Objects.requireNonNull(origin);
        }
    }
}
