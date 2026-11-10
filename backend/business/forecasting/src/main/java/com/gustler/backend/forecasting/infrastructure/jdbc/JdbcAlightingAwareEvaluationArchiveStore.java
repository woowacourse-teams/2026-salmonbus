package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.evaluation.AlightingAwareEvaluationArchiveStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 후보·근거를 조회하고, 검증한 원본과 DB가 여전히 같을 때 보관·삭제·복원을 커밋한다. */
@Repository
public class JdbcAlightingAwareEvaluationArchiveStore implements AlightingAwareEvaluationArchiveStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> EVIDENCE_FIELDS = List.of(
        "pre_observation_id", "post_observation_id", "closure_observation_id",
        "previous_post_observation_id", "previous_closure_observation_id", "capacity_observation_id");
    private static final String KEY_PREDICATE = """
        vehicle_observation_id=:id AND target_stop_order=:stop AND target_kind=:target
            AND label_version=:version AND quality_revision=:revision
        """;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcAlightingAwareEvaluationArchiveStore(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(15);
    }

    @Override
    public Optional<String> verifiedForPurge() {
        return jdbc.sql("""
            SELECT object_hash FROM alighting_evaluation_archive
            WHERE storage_state='LIVE' AND restored_at IS NULL AND abandoned_at IS NULL
            ORDER BY verified_at LIMIT 1
            """)
            .query(String.class).optional();
    }

    @Override
    public boolean retireChanged(String hash, List<AlightingAwareEvaluationArchiveStore.Row> rows) {
        Boolean retired = transaction.execute(status -> {
            for (AlightingAwareEvaluationArchiveStore.Row row : rows) {
                JsonNode original = JSON.readTree(row.originalJson());
                AlightingAwareEvaluationArchiveStore.Key key = key(original);
                if (!isPurgeCandidate(key, hash)) {
                    continue;
                }
                List<String> current = lockResult(key);
                if (hasChanged(original, current, JSON.readTree(row.evidenceJson()))) {
                    jdbc.sql("""
                        UPDATE alighting_evaluation_archive SET abandoned_at=clock_timestamp()
                        WHERE object_hash=:hash AND storage_state='LIVE' AND restored_at IS NULL
                          AND abandoned_at IS NULL
                        """).param("hash", hash).update();
                    return true;
                }
            }
            return false;
        });
        return Boolean.TRUE.equals(retired);
    }

    private boolean isPurgeCandidate(AlightingAwareEvaluationArchiveStore.Key key, String hash) {
        return bind(jdbc.sql("SELECT EXISTS(SELECT 1 FROM alighting_evaluation_archive WHERE "
            + KEY_PREDICATE + " AND object_hash=:hash AND storage_state='LIVE' "
            + "AND restored_at IS NULL AND abandoned_at IS NULL)"), key)
            .param("hash", hash).query(Boolean.class).single();
    }

    private boolean hasChanged(JsonNode original, List<String> current, JsonNode evidence) {
        if (current.isEmpty()) {
            return true;
        }
        return !JSON.readTree(current.getFirst()).equals(original) || !readEvidence(original).equals(evidence);
    }

    @Override
    public List<AlightingAwareEvaluationArchiveStore.Row> candidates(final int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("AlightingAware 이관 페이지는 1~1000개여야 한다");
        }
        List<AlightingAwareEvaluationArchiveStore.Row> rows = new ArrayList<>();
        for (String original : candidateResults(limit)) {
            rows.add(new AlightingAwareEvaluationArchiveStore.Row(original, readEvidence(JSON.readTree(original)).toString()));
        }
        return List.copyOf(rows);
    }

    private List<String> candidateResults(final int limit) {
        return jdbc.sql("""
            SELECT to_jsonb(r)::text FROM alighting_forecast_evaluation_result r
            WHERE r.scored_at<date_trunc('day',CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul'
              AND NOT EXISTS(SELECT 1 FROM alighting_evaluation_archive a
                WHERE a.vehicle_observation_id=r.vehicle_observation_id AND a.target_stop_order=r.target_stop_order
                  AND a.target_kind=r.target_kind AND a.label_version=r.label_version
                  AND a.quality_revision=r.quality_revision)
              AND NOT EXISTS(SELECT 1 FROM alighting_forecast_evaluation_work w
                WHERE w.vehicle_observation_id=r.vehicle_observation_id AND w.target_stop_order=r.target_stop_order)
            ORDER BY r.scored_at,r.vehicle_observation_id,r.target_stop_order,r.target_kind LIMIT :page
            """)
            .param("page", limit).query(String.class).list();
    }

    @Override
    public void archive(
        List<AlightingAwareEvaluationArchiveStore.Row> verifiedRows,
        String hash,
        final boolean deleteEnabled) {
        transaction.executeWithoutResult(status -> {
            for (AlightingAwareEvaluationArchiveStore.Row row : verifiedRows) {
                archiveRow(row, hash, deleteEnabled);
            }
        });
    }

    private void archiveRow(AlightingAwareEvaluationArchiveStore.Row original, String hash, final boolean deleteEnabled) {
        JsonNode row = JSON.readTree(original.originalJson());
        AlightingAwareEvaluationArchiveStore.Key key = key(row);
        if (isRetained(key)) {
            return;
        }
        List<String> current = lockResult(key);
        if (current.isEmpty()) {
            return;
        }
        assertUnchanged(row, JSON.readTree(current.getFirst()), JSON.readTree(original.evidenceJson()));
        if (hasPendingWork(key)) {
            return;
        }
        recordArchive(key, hash, deleteEnabled);
        if (deleteEnabled) {
            bind(jdbc.sql("DELETE FROM alighting_forecast_evaluation_result WHERE " + KEY_PREDICATE), key).update();
        }
    }

    private boolean isRetained(AlightingAwareEvaluationArchiveStore.Key key) {
        return bind(jdbc.sql("SELECT EXISTS(SELECT 1 FROM alighting_evaluation_archive WHERE "
            + KEY_PREDICATE + " AND (restored_at IS NOT NULL OR abandoned_at IS NOT NULL))"), key).query(Boolean.class).single();
    }

    private List<String> lockResult(AlightingAwareEvaluationArchiveStore.Key key) {
        return bind(jdbc.sql("SELECT to_jsonb(r)::text FROM alighting_forecast_evaluation_result r WHERE "
            + KEY_PREDICATE + " FOR UPDATE"), key).query(String.class).list();
    }

    private void assertUnchanged(JsonNode original, JsonNode current, JsonNode evidence) {
        if (!current.equals(original) || !readEvidence(original).equals(evidence)) {
            throw new IllegalStateException("이관 중 AlightingAware 원본이나 관측 근거가 변경됐다");
        }
    }

    private boolean hasPendingWork(AlightingAwareEvaluationArchiveStore.Key key) {
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM alighting_forecast_evaluation_work
                WHERE vehicle_observation_id=:id AND target_stop_order=:stop)
            """)
            .param("id", key.id()).param("stop", key.stop()).query(Boolean.class).single();
    }

    private void recordArchive(AlightingAwareEvaluationArchiveStore.Key key, String hash, final boolean deleteEnabled) {
        bind(jdbc.sql("""
            INSERT INTO alighting_evaluation_archive(vehicle_observation_id,target_stop_order,target_kind,label_version,
                quality_revision,route_version_id,label_value,scoring_state,reason,event_at,scored_at,
                object_hash,storage_state)
            SELECT vehicle_observation_id,target_stop_order,target_kind,label_version,quality_revision,
                route_version_id,label_value,scoring_state,reason,event_at,scored_at,:hash,:state
            FROM alighting_forecast_evaluation_result WHERE
            """ + KEY_PREDICATE + """
            ON CONFLICT(vehicle_observation_id,target_stop_order,target_kind,label_version,quality_revision)
                DO UPDATE SET storage_state=EXCLUDED.storage_state
                WHERE alighting_evaluation_archive.object_hash=EXCLUDED.object_hash
            """), key)
            .param("hash", hash).param("state", storageState(deleteEnabled)).update();
        if (!objectHash(key).equals(hash)) {
            throw new IllegalStateException("이미 보관한 AlightingAware 원본과 다르다");
        }
    }

    private String storageState(final boolean deleteEnabled) {
        if (deleteEnabled) {
            return "PURGED";
        }
        return "LIVE";
    }

    @Override
    public String objectHash(AlightingAwareEvaluationArchiveStore.Key key) {
        return bind(jdbc.sql("SELECT object_hash FROM alighting_evaluation_archive WHERE " + KEY_PREDICATE), key)
            .query(String.class).single();
    }

    @Override
    public int restore(AlightingAwareEvaluationArchiveStore.Key key, String hash, String originalJson) {
        JsonNode saved = JSON.readTree(originalJson);
        if (!key.equals(key(saved))) {
            throw new IllegalArgumentException("복원 원본의 식별자가 다르다");
        }
        ObjectNode restoredRow = archivedEvidenceReference(saved, hash);
        Integer restored = transaction.execute(status -> restoreRow(key, hash, saved, restoredRow));
        if (restored == null) {
            return 0;
        }
        return restored;
    }

    /** 원자료가 삭제된 뒤에도 복원하며, 근거는 검증한 archive hash로 참조한다. */
    private ObjectNode archivedEvidenceReference(JsonNode saved, String hash) {
        ObjectNode restoredRow = (ObjectNode) saved.deepCopy();
        for (String field : EVIDENCE_FIELDS) {
            restoredRow.putNull(field);
        }
        restoredRow.put("evidence_archive_hash", hash);
        return restoredRow;
    }

    private int restoreRow(
        AlightingAwareEvaluationArchiveStore.Key key,
        String hash,
        JsonNode saved,
        ObjectNode restoredRow
    ) {
        String locked = bind(jdbc.sql("SELECT object_hash FROM alighting_evaluation_archive WHERE "
            + KEY_PREDICATE + " FOR UPDATE"), key).query(String.class).single();
        if (!locked.equals(hash)) {
            throw new IllegalStateException("검증 후 이관 원본이 변경됐다");
        }
        assertRestorable(lockResult(key), saved, restoredRow);
        final int count = insertRestoredResult(restoredRow);
        bind(jdbc.sql("UPDATE alighting_evaluation_archive SET storage_state='LIVE',restored_at=CURRENT_TIMESTAMP WHERE "
            + KEY_PREDICATE), key).update();
        return count;
    }

    private void assertRestorable(List<String> current, JsonNode saved, ObjectNode restoredRow) {
        if (current.isEmpty()) {
            return;
        }
        JsonNode existing = JSON.readTree(current.getFirst());
        if (!existing.equals(restoredRow) && !existing.equals(saved)) {
            throw new IllegalStateException("복원 위치에 다른 AlightingAware 결과가 있다");
        }
    }

    private int insertRestoredResult(ObjectNode restoredRow) {
        return jdbc.sql("""
            INSERT INTO alighting_forecast_evaluation_result
            SELECT * FROM jsonb_populate_record(NULL::alighting_forecast_evaluation_result,CAST(:row AS jsonb))
            ON CONFLICT(vehicle_observation_id,target_stop_order,target_kind,label_version,quality_revision) DO NOTHING
            """)
            .param("row", restoredRow.toString()).update();
    }

    private JsonNode readEvidence(JsonNode row) {
        ArrayNode evidence = JSON.createArrayNode();
        for (String field : EVIDENCE_FIELDS) {
            if (row.path(field).isNull()) {
                continue;
            }
            JsonNode source = readObservationSnapshot(row.path(field).longValue());
            evidence.add(JSON.createObjectNode().put("field", field).set("source", source));
        }
        return evidence;
    }

    private JsonNode readObservationSnapshot(final long observationId) {
        String source = jdbc.sql("""
            SELECT jsonb_build_object('observation',to_jsonb(o),'batch',to_jsonb(b),
                'effectiveTrip',q.vehicle_trip_key,'direction',q.quality_direction)::text
            FROM vehicle_observation o JOIN observation_batch b ON b.id=o.observation_batch_id
            JOIN operational_observation q ON q.id=o.id WHERE o.id=:id FOR SHARE OF o,b
            """)
            .param("id", observationId).query(String.class).single();
        return JSON.readTree(source);
    }

    private static AlightingAwareEvaluationArchiveStore.Key key(JsonNode row) {
        return new AlightingAwareEvaluationArchiveStore.Key(row.path("vehicle_observation_id").longValue(),
            row.path("target_stop_order").intValue(), row.path("target_kind").asString(),
            row.path("label_version").asString(), row.path("quality_revision").longValue());
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec query, AlightingAwareEvaluationArchiveStore.Key key) {
        return query.param("id", key.id()).param("stop", key.stop()).param("target", key.target())
            .param("version", key.version()).param("revision", key.revision());
    }
}
