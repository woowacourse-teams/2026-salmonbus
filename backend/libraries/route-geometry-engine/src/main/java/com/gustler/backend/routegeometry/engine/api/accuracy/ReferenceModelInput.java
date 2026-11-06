package com.gustler.backend.routegeometry.engine.api.accuracy;

import com.gustler.backend.routegeometry.api.RouteModelFacts;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import java.util.Objects;

/** Immutable model inputs. Receipt identities are provenance; consumed source roles are semantic identity. */
public record ReferenceModelInput(String inputDigest,String evidenceRevision,String sourceManifestId,
        RouteModelFacts semanticFacts,AccuracyCandidate.ModelIdentity model,String referenceBundleDigest,
        String sourcePolicyDigest) {
    public ReferenceModelInput {
        Objects.requireNonNull(semanticFacts);Objects.requireNonNull(model);
        for(String digest:new String[]{inputDigest,evidenceRevision,referenceBundleDigest,sourcePolicyDigest,
                model.codeSha256(),model.configSha256()})
            if(digest==null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Model input requires pinned identities");
        if(!inputDigest.equals(semanticFacts.inputDigest())) throw new IllegalArgumentException("Model role facts belong to another input");
        if(sourceManifestId!=null && !sourceManifestId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source provenance");
        if(model.modelId().isBlank() || model.modelId().contains("\n")) throw new IllegalArgumentException("Invalid model identity");
    }
    public static ReferenceModelInput of(RouteGeometry source,RouteModelFacts facts,AccuracyCandidate.ModelIdentity model,
            String referenceBundleDigest,String sourcePolicyDigest) {
        return new ReferenceModelInput(RouteDisplayGeneration.input(source).digest(),source.shape().evidence().revision(),
                source.shape().sourceManifestId(),facts,model,referenceBundleDigest,sourcePolicyDigest);
    }
    /** This is deliberately separate from the unchanged baseline and reviewed-manual input contracts. */
    public String generationKey() {
        return RouteDisplayGeneration.sha256(String.join("\n","reference-model-input-v1",inputDigest,evidenceRevision,
            semanticFacts.factsDigest(),model.modelId(),model.codeSha256(),model.configSha256(),referenceBundleDigest,sourcePolicyDigest,
            sourceManifestId==null?"UNBOUND_SOURCE":"CAPTURED_INPUT_V1"));
    }
    public boolean semanticMatch(ReferenceModelInput current) {
        return current!=null && generationKey().equals(current.generationKey());
    }
    public boolean additionalRolesResolved() { return semanticFacts.availability()==RouteModelFacts.Availability.RESOLVED; }
}
