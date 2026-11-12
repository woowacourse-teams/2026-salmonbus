package com.gustler.backend.api.route.infrastructure.jdbc;

import com.gustler.backend.api.route.application.RouteDisplayRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Repository
public class JdbcRouteDisplayRepository implements RouteDisplayRepository {
    private final JdbcClient jdbc;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    public JdbcRouteDisplayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Manual> findManual(String routeId, String inputDigest) {
        return jdbc.sql(
                        """
SELECT display.document::text, display.content_digest FROM route_display_geometry display
JOIN route ON route.id=display.route_id
WHERE route.public_route_id=? AND route.source_id='GBIS'
  AND display.input_digest=? AND display.disabled_at IS NULL
ORDER BY display.registered_at DESC,display.id DESC LIMIT 1
""")
                .params(routeId, inputDigest)
                .query((row, index) -> new Manual(row.getString(1), row.getString(2)))
                .optional();
    }
    @Override
    public Optional<Automatic> findAutomatic(String generationKey, String routeId, String inputDigest,
            String evidenceRevision, String algorithmRevision) {
        return jdbc.sql("""
            SELECT document,document_sha256,source_manifest_id FROM route_display_automatic_artifact
            WHERE generation_key=? AND route_id=? AND input_digest=?
              AND evidence_revision=? AND algorithm_revision=?
            """).params(generationKey, routeId, inputDigest, evidenceRevision, algorithmRevision)
            .query((row,index) -> new Automatic(row.getString(1),row.getString(2),row.getString(3))).optional();
    }
    @Override
    public Map<String, GenerationState> findGenerationStates(String routeId, String inputDigest,
            String evidenceRevision, List<String> generationKeys) {
        if (generationKeys.isEmpty()) return Map.of();
        var rows = jdbc.sql("""
            SELECT generation_key,status,
                CASE WHEN status IN ('PENDING','SUPERSEDED') THEN attempts < attempt_limit
                     WHEN status='RUNNING' THEN lease_until > clock_timestamp() OR attempts < attempt_limit
                     ELSE false END AS can_progress,
                (status='PENDING' AND attempts=0 OR status='RUNNING' AND attempts=1 AND lease_until>clock_timestamp()) AS initial_attempt
            FROM route_display_generation_job
            WHERE route_id=:routeId AND input_digest=:inputDigest AND evidence_revision=:evidenceRevision AND generation_key IN (:keys)
            """).param("routeId",routeId).param("inputDigest",inputDigest).param("evidenceRevision",evidenceRevision)
            .param("keys",generationKeys)
            .query((row,n) -> Map.entry(row.getString(1),new GenerationState(row.getString(2),row.getBoolean(3),row.getBoolean(4)))).list();
        var states = new HashMap<String, GenerationState>();
        rows.forEach(entry -> states.put(entry.getKey(),entry.getValue()));
        return Map.copyOf(states);
    }

    @Override
    public Optional<GenerationState> findSourceRecovery(String routeId, long referenceVersionId) {
        return jdbc.sql("""
            SELECT j.status,
                CASE WHEN j.status='PENDING' THEN j.attempts<5
                     WHEN j.status='RUNNING' THEN j.lease_until>clock_timestamp() OR j.attempts<5
                     ELSE false END
            FROM route_geometry_recovery j JOIN route r ON r.id=j.route_id
            WHERE j.route_version_id=? AND r.public_route_id=? AND r.source_id='GBIS'
            """).params(referenceVersionId,routeId)
            .query((row,n)->new GenerationState(row.getString(1),row.getBoolean(2),false)).optional();
    }

    @Override
    public Optional<SourceManifest> findSourceManifest(String id) {
        return jdbc.sql("""
            SELECT m.route_id,m.reference_version_id,m.input_digest,m.evidence_revision,m.acquisition_manifest_id,m.document,r.public_route_id
            FROM route_input_manifest m JOIN route_version v ON v.id=m.reference_version_id AND v.route_id=m.route_id
            JOIN route r ON r.id=m.route_id WHERE m.manifest_id=?
            """).param(id).query((row,n) -> {
                String document=row.getString(6);
                var m=json.readValue(document,com.gustler.backend.routegeometry.api.RouteInputProvenance.class);
                m.verifyStored(document,id,row.getLong(1),row.getLong(2),row.getString(3),row.getString(4),row.getString(5),row.getString(7));
                return new SourceManifest(m.inputDigest(),m.evidenceRevision(),m.referenceVersionId());
            }).optional();
    }

    /**
     * 각 조회와 같은 조건으로 행 식별값과 작업·복구 상태를 한 SQL로 읽는다.
     *
     * <p>{@code xmin}은 행을 넣거나 고친 트랜잭션 번호다. 수동 형상은 content_digest를 그대로 둔 채 문서만 고칠 수 있어 이 값으로 바뀜을
     * 알아챈다. 자동 산출물은 트리거로 수정·삭제가 막혀 있지만 TRUNCATE 뒤 같은 생성 키로 다시 넣는 경우를 같은 값으로 구별한다. 입력 출처
     * 문서는 manifest_id가 그 문서의 SHA-256이라는 CHECK가 있어 행의 나머지 열만 읽는다. 작업·복구 상태의 진행 가능 여부는
     * {@link #findGenerationStates}·{@link #findSourceRecovery}와 같은 식으로 계산한다. 자료 복구 행이 둘 이상이면 그 조회처럼 실패한다.
     */
    @Override
    public Optional<StoredRead> findStoredInputs(String routeId, final long referenceVersionId, String inputDigest,
            String evidenceRevision, String sourceManifestId, List<String> generationKeys) {
        List<String> keys = generationKeys.isEmpty() ? List.of("") : generationKeys;
        return Optional.of(jdbc.sql("""
            SELECT manual.id,manual.content_digest,manual.row_version,manual.document,
                manifest.route_id,manifest.reference_version_id,manifest.input_digest,manifest.evidence_revision,
                manifest.acquisition_manifest_id,manifest.public_route_id,
                artifacts.rows,states.rows,recovery.status,recovery.can_progress
            FROM (SELECT 1) anchor
            LEFT JOIN LATERAL (
                SELECT display.id,display.content_digest,display.xmin::text AS row_version,display.document::text AS document
                FROM route_display_geometry display JOIN route ON route.id=display.route_id
                WHERE route.public_route_id=:routeId AND route.source_id='GBIS'
                  AND display.input_digest=:inputDigest AND display.disabled_at IS NULL
                ORDER BY display.registered_at DESC,display.id DESC LIMIT 1
            ) manual ON true
            LEFT JOIN LATERAL (
                SELECT m.route_id,m.reference_version_id,m.input_digest,m.evidence_revision,m.acquisition_manifest_id,
                    r.public_route_id
                FROM route_input_manifest m JOIN route_version v ON v.id=m.reference_version_id AND v.route_id=m.route_id
                JOIN route r ON r.id=m.route_id WHERE m.manifest_id=:manifestId
            ) manifest ON true
            CROSS JOIN LATERAL (
                SELECT COALESCE(json_agg(json_build_object('generationKey',a.generation_key,
                    'algorithmRevision',a.algorithm_revision,'documentSha256',a.document_sha256,
                    'sourceManifestId',a.source_manifest_id,'rowVersion',a.xmin::text)
                    ORDER BY a.generation_key),'[]'::json)::text AS rows
                FROM route_display_automatic_artifact a
                WHERE a.route_id=:routeId AND a.input_digest=:inputDigest AND a.evidence_revision=:evidenceRevision
                  AND a.generation_key IN (:keys)
            ) artifacts
            CROSS JOIN LATERAL (
                SELECT COALESCE(json_agg(json_build_object('generationKey',j.generation_key,'status',j.status,
                    'canProgress',CASE WHEN j.status IN ('PENDING','SUPERSEDED') THEN j.attempts < j.attempt_limit
                         WHEN j.status='RUNNING' THEN j.lease_until > clock_timestamp() OR j.attempts < j.attempt_limit
                         ELSE false END,
                    'initialAttempt',(j.status='PENDING' AND j.attempts=0
                         OR j.status='RUNNING' AND j.attempts=1 AND j.lease_until>clock_timestamp()))),'[]'::json)::text AS rows
                FROM route_display_generation_job j
                WHERE j.route_id=:routeId AND j.input_digest=:inputDigest AND j.evidence_revision=:evidenceRevision
                  AND j.generation_key IN (:keys)
            ) states
            LEFT JOIN LATERAL (
                SELECT j.status,
                    CASE WHEN j.status='PENDING' THEN j.attempts<5
                         WHEN j.status='RUNNING' THEN j.lease_until>clock_timestamp() OR j.attempts<5
                         ELSE false END AS can_progress
                FROM route_geometry_recovery j JOIN route r ON r.id=j.route_id
                WHERE j.route_version_id=:referenceVersionId AND r.public_route_id=:routeId AND r.source_id='GBIS'
            ) recovery ON true
            """)
            .param("routeId", routeId).param("inputDigest", inputDigest).param("evidenceRevision", evidenceRevision)
            .param("manifestId", sourceManifestId).param("keys", keys).param("referenceVersionId", referenceVersionId)
            .query((row, index) -> {
                ManualRow manual = null;
                if (row.getObject(1) != null) {
                    manual = new ManualRow(row.getLong(1), row.getString(2), row.getString(3));
                }
                ManifestRow manifest = null;
                if (row.getObject(5) != null) {
                    manifest = new ManifestRow(sourceManifestId, row.getLong(5), row.getLong(6), row.getString(7),
                        row.getString(8), row.getString(9), row.getString(10));
                }
                GenerationState recovery = null;
                if (row.getString(13) != null) {
                    recovery = new GenerationState(row.getString(13), row.getBoolean(14), false);
                }
                StoredInputs inputs = new StoredInputs(manual, manifest, artifactsOf(row.getString(11)),
                    statesOf(row.getString(12)), recovery);
                return new StoredRead(inputs, row.getString(4));
            })
            .single());
    }

    private List<ArtifactRow> artifactsOf(String rows) {
        return json.readValue(rows, new tools.jackson.core.type.TypeReference<List<ArtifactRow>>() { });
    }

    private Map<String, GenerationState> statesOf(String rows) {
        List<StateRow> read = json.readValue(rows, new tools.jackson.core.type.TypeReference<List<StateRow>>() { });
        Map<String, GenerationState> states = new HashMap<>();
        read.forEach(state -> states.put(state.generationKey(),
            new GenerationState(state.status(), state.canProgress(), state.initialAttempt())));
        return Map.copyOf(states);
    }

    /** 생성 작업 상태 한 행. {@link #findGenerationStates}가 읽는 열과 같다. */
    private record StateRow(String generationKey, String status, boolean canProgress, boolean initialAttempt) {}
}
