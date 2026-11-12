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
    record GenerationState(String status, boolean canProgress, boolean initialAttempt) {
        public GenerationState(String status, boolean canProgress) {
            this(status, canProgress, canProgress && (status.equals("PENDING") || status.equals("RUNNING")));
        }
    }

    default Optional<GenerationState> findSourceRecovery(String routeId, long referenceVersionId) {
        return Optional.empty();
    }

    default Optional<SourceManifest> findSourceManifest(String id) { return Optional.empty(); }
    record SourceManifest(String inputDigest,String evidenceRevision,long referenceVersionId) {}

    record Automatic(String document, String sha256,String sourceManifestId) {
        public Automatic(String document,String sha256) { this(document,sha256,null); }
    }

    record Manual(String document, String revision) {}

    /**
     * 표시 선택이 같은 입력으로 읽을 저장 자료의 식별값과 상태를 한 번에 읽는다. 자동 산출물과 입력 출처 문서는 읽지 않는다.
     *
     * <p>계약: {@link StoredRead#inputs()}가 같으면 {@link #findManual}, {@link #findSourceManifest}, {@link #findAutomatic},
     * {@link #findGenerationStates}, {@link #findSourceRecovery}를 같은 인자로 불렀을 때의 결과도 같아야 한다.
     * {@code generationKeys}는 표시 선택이 찾을 수 있는 생성 키를 모두 담는다. 작업 상태와 자료 복구 상태는 각 조회와 같은 값이고,
     * 수동 형상이 있으면 그 문서도 담는다. 표시 선택은 이 값으로 위 조회를 대신하므로, 구현은 한 SQL로 읽어 조회 수를 늘리지 않는다.
     * 식별값을 읽지 못하는 저장소는 빈 값을 준다.
     */
    default Optional<StoredRead> findStoredInputs(String routeId, final long referenceVersionId, String inputDigest,
            String evidenceRevision, String sourceManifestId, List<String> generationKeys) {
        return Optional.empty();
    }

    /** 저장 자료의 식별값과 상태, 그리고 수동 형상이 있으면 그 문서. 문서는 식별값이 정하므로 지문에는 {@code inputs}만 쓴다. */
    record StoredRead(StoredInputs inputs, String manualDocument) {}

    /** 현재 수동 형상 행. {@code rowVersion}은 같은 행의 문서나 비활성화 여부가 바뀌어도 달라진다. */
    record ManualRow(long id, String contentDigest, String rowVersion) {}

    /** 입력 출처 행. 문서는 {@code manifestId}가 내용 해시이므로 담지 않는다. */
    record ManifestRow(String manifestId, long routeId, long referenceVersionId, String inputDigest,
            String evidenceRevision, String acquisitionManifestId, String publicRouteId) {}

    /** 자동 산출물 행. {@code rowVersion}은 같은 생성 키로 다시 넣은 행을 구별한다. */
    record ArtifactRow(String generationKey, String algorithmRevision, String documentSha256,
            String sourceManifestId, String rowVersion) {}

    /** 수동 형상·입력 출처·자료 복구는 없으면 null이다. 작업 상태는 시각에 따라 달라지므로 읽은 값을 그대로 담는다. */
    record StoredInputs(ManualRow manual, ManifestRow manifest, List<ArtifactRow> artifacts,
            Map<String, GenerationState> generationStates, GenerationState sourceRecovery) {
        public StoredInputs {
            artifacts = List.copyOf(artifacts);
            generationStates = Map.copyOf(generationStates);
        }
    }
}
