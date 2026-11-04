package com.gustler.backend.api.route.application;

import java.util.Optional;
import java.util.List;
import java.util.Map;

/** API는 검수 자료를 읽기만 한다. 현재 입력과 다른 문서를 후보로 반환하지 않는다. */
public interface RouteDisplayRepository {
    Optional<Manual> findManual(String routeId, String inputDigest);

    default Optional<Automatic> findAutomatic(String generationKey, String routeId, String inputDigest,
            String evidenceRevision, String algorithmRevision) {
        return Optional.empty();
    }

    /** Current-key state only; reads never enqueue or revive work. */
    default Map<String, GenerationState> findGenerationStates(String routeId, String inputDigest,
            String evidenceRevision, List<String> generationKeys) { return Map.of(); }
    record GenerationState(String status, boolean canProgress) {}

    default Optional<SourceManifest> findSourceManifest(String id) { return Optional.empty(); }
    record SourceManifest(String inputDigest,String evidenceRevision,long referenceVersionId) {}

    record Automatic(String document, String sha256,String sourceManifestId) {
        public Automatic(String document,String sha256) { this(document,sha256,null); }
    }

    record Manual(String document, String revision) {}
}
