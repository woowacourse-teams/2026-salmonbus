package com.gustler.backend.routegeometry.api;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Parsed sealed provenance; its claimed digests are recomputed, never trusted as labels. */
public record RouteInputProvenance(String format,long routeId,long referenceVersionId,
        String acquisitionManifestId,String inputDigest,String evidenceRevision,
        RouteDisplayInput input,RouteShapeEvidence evidence) {
    public RouteInputProvenance {
        if (!"route-input-manifest-v1".equals(format) || routeId<1 || referenceVersionId<1
                || acquisitionManifestId==null || !acquisitionManifestId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid input provenance metadata");
        Objects.requireNonNull(input);Objects.requireNonNull(evidence);
        if (!input.digest().equals(inputDigest) || !evidence.revision().equals(evidenceRevision))
            throw new IllegalArgumentException("Input provenance digest mismatch");
    }
    public void verifyStored(String document,String manifestId,long storedRouteId,long storedVersion,
            String storedInputDigest,String storedEvidenceRevision,String storedAcquisition,String publicRouteId) {
        if (!RouteSourceReceipt.sha256(document.getBytes(StandardCharsets.UTF_8)).equals(manifestId)
                || routeId!=storedRouteId || referenceVersionId!=storedVersion
                || !inputDigest.equals(storedInputDigest) || !evidenceRevision.equals(storedEvidenceRevision)
                || !acquisitionManifestId.equals(storedAcquisition) || !input.routeId().equals(publicRouteId))
            throw new IllegalArgumentException("Stored input provenance differs from its sealed document");
    }
}
