package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStore;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveRetentionStore;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.State;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcEvaluationArchiveStore implements EvaluationArchiveStore, EvaluationArchiveRetentionStore {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;
    private final RouteDataQualityAccess quality;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public JdbcEvaluationArchiveStore(JdbcClient jdbc, RouteDataQualityAccess quality, Clock clock,
        PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.quality = quality;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(2);
    }

    @Override
    public Optional<EvaluationArchiveBatch> reserve(long routeVersionId, List<Key> candidates) {
        if (routeVersionId <= 0 || candidates == null || candidates.isEmpty() || candidates.size() > 100
            || candidates.stream().anyMatch(key -> key == null)) {
            throw new IllegalArgumentException("노선과 한정된 정산 후보가 필요하다");
        }
        List<Key> keys = candidates.stream().distinct()
            .sorted(Comparator.comparingLong(Key::observationId).thenComparingInt(Key::stopOrder)).toList();
        return inTransaction(() -> {
            long revision = quality.lock(routeVersionId);
            if (quality.anyInvestigationPending(routeVersionId)) {
                return Optional.empty();
            }
            var before = clock.instant().atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL)
                .toInstant().atOffset(ZoneOffset.UTC);
            List<Row> rows = new ArrayList<>();
            for (Key key : keys) {
                jdbc.sql("""
                    SELECT to_jsonb(e)::text AS original FROM forecast_evaluation_result e
                    WHERE e.vehicle_observation_id=:observation AND e.target_stop_order=:stop
                      AND e.route_version_id=:route AND e.scoring_state<>'PENDING'
                      AND e.scored_at<:before AND (e.arrived_at IS NULL OR e.arrived_at<:before)
                      AND NOT EXISTS(SELECT 1 FROM evaluation_archive_member m
                          WHERE m.vehicle_observation_id=e.vehicle_observation_id
                            AND m.target_stop_order=e.target_stop_order)
                    FOR UPDATE OF e
                    """).param("observation", key.observationId()).param("stop", key.stopOrder())
                    .param("route", routeVersionId).param("before", before).query(String.class).optional()
                    .ifPresent(json -> rows.add(new Row(key, json, digest(json))));
            }
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            UUID id = UUID.randomUUID();
            var batch = jdbc.sql("""
                INSERT INTO evaluation_archive_batch(id,route_version_id,quality_revision,lease_token,lease_until,row_count)
                VALUES(:id,:route,:revision,:token,clock_timestamp()+interval '5 minutes',:count)
                RETURNING *
                """).param("id", id).param("route", routeVersionId).param("revision", revision)
                .param("token", UUID.randomUUID()).param("count", rows.size())
                .query(JdbcEvaluationArchiveStore::batchOf).single();
            for (Row row : rows) {
                jdbc.sql("""
                    INSERT INTO evaluation_archive_member(vehicle_observation_id,target_stop_order,
                        route_version_id,batch_id,original_sha256) VALUES(?,?,?,?,?)
                    """).params(row.key().observationId(), row.key().stopOrder(), routeVersionId, id, row.sha256())
                    .update();
            }
            return Optional.of(batch);
        });
    }

    @Override
    public Optional<EvaluationArchiveBatch> reclaim(UUID batchId) {
        return inTransaction(() -> {
            long route = jdbc.sql("SELECT route_version_id FROM evaluation_archive_batch WHERE id=?")
                .param(batchId).query(Long.class).single();
            quality.lock(route);
            return jdbc.sql("""
                UPDATE evaluation_archive_batch SET lease_token=:token,
                    lease_until=clock_timestamp()+interval '5 minutes'
                WHERE id=:id AND lease_until<=clock_timestamp() AND abandoned_at IS NULL RETURNING *
                """).param("id", batchId).param("token", UUID.randomUUID())
                .query(JdbcEvaluationArchiveStore::batchOf).optional();
        });
    }

    @Override
    public List<Row> readOwned(EvaluationArchiveBatch batch) {
        return inTransaction(() -> {
            assertOwned(batch);
            return readUnchanged(batch);
        });
    }

    @Override
    public void recordVerified(EvaluationArchiveBatch batch, String manifestSha256) {
        if (manifestSha256 == null || !manifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("파일 설명서의 검증값이 필요하다");
        }
        inTransaction(() -> {
            assertOwned(batch);
            readUnchanged(batch);
            int changed = jdbc.sql("""
                UPDATE evaluation_archive_batch SET state='VERIFIED',manifest_sha256=:digest,
                    verified_at=COALESCE(verified_at,clock_timestamp())
                WHERE id=:id AND lease_token=:token AND lease_until>clock_timestamp()
                  AND (manifest_sha256 IS NULL OR manifest_sha256=:digest)
                """).param("id", batch.id()).param("token", batch.leaseToken())
                .param("digest", manifestSha256).update();
            if (changed != 1) {
                throw new IllegalStateException("작업 소유권이 만료됐거나 이미 검증한 파일과 다르다");
            }
            return null;
        });
    }

    @Override
    public Snapshot preparePurge(EvaluationArchiveBatch owned) {
        return inTransaction(() -> {
            Snapshot snapshot = retentionOf(owned);
            if (snapshot.location() == Location.PURGED) assertNoLiveRows(snapshot.batch());
            else assertPurgeReady(snapshot);
            return snapshot;
        });
    }

    @Override
    public Snapshot prepareRestore(EvaluationArchiveBatch owned) {
        return inTransaction(() -> retentionOf(owned));
    }

    @Override
    public int purge(Snapshot prepared, List<Row> verifiedRows) {
        return inTransaction(() -> {
            Snapshot current = assertSameRetention(prepared);
            if (current.location() == Location.PURGED) {
                assertNoLiveRows(current.batch());
                return 0;
            }
            assertPurgeReady(current);
            assertArchivedRows(current.batch(), verifiedRows);
            readUnchanged(current.batch());
            int removed = jdbc.sql("""
                DELETE FROM forecast_evaluation_result e USING evaluation_archive_member m
                WHERE m.batch_id=:batch AND e.vehicle_observation_id=m.vehicle_observation_id
                  AND e.target_stop_order=m.target_stop_order AND e.route_version_id=m.route_version_id
                """).param("batch", current.batch().id()).update();
            if (removed != current.batch().rowCount()) {
                throw new IllegalStateException("검증한 원본 전체가 삭제되지 않았다");
            }
            recordLocation(current, Location.PURGED);
            return removed;
        });
    }

    @Override
    public int restore(Snapshot prepared, List<Row> verifiedRows) {
        return inTransaction(() -> {
            Snapshot current = assertSameRetention(prepared);
            if (current.location() == Location.LIVE) {
                readUnchanged(current.batch());
                return 0;
            }
            assertArchivedRows(current.batch(), verifiedRows);
            if (jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM evaluation_archive_member m
                    JOIN forecast_evaluation_pending p USING(vehicle_observation_id,target_stop_order)
                    WHERE m.batch_id=?)
                """).param(current.batch().id()).query(Boolean.class).single()) {
                throw new IllegalStateException("복원 대상에 정산 대기가 존재한다");
            }
            int restored = 0;
            for (Row row : verifiedRows) {
                var existing = jdbc.sql("""
                    SELECT to_jsonb(e)::text FROM forecast_evaluation_result e
                    WHERE vehicle_observation_id=? AND target_stop_order=? FOR UPDATE
                    """).params(row.key().observationId(), row.key().stopOrder()).query(String.class).optional();
                if (existing.isPresent() && !digest(existing.get()).equals(row.sha256())) {
                    throw new IllegalStateException("현재 정산과 복원 원본이 다르므로 덮어쓰지 않는다");
                }
                restored += jdbc.sql("""
                    INSERT INTO forecast_evaluation_result
                    SELECT * FROM jsonb_populate_record(NULL::forecast_evaluation_result,CAST(:original AS jsonb))
                    ON CONFLICT(vehicle_observation_id,target_stop_order) DO NOTHING
                    """).param("original", row.originalJson()).update();
            }
            // 직접 쓰기와 충돌했거나 JSON의 열·표현이 달라져도 부분 복원을 커밋하지 않는다.
            readUnchanged(current.batch());
            recordLocation(current, Location.LIVE);
            return restored;
        });
    }

    private Snapshot retentionOf(EvaluationArchiveBatch owned) {
        long revision = quality.lock(owned.routeVersionId());
        if (quality.anyInvestigationPending(owned.routeVersionId())) {
            throw new IllegalStateException("품질 조사 중에는 원본 위치를 바꾸지 않는다");
        }
        return jdbc.sql("""
            SELECT * FROM evaluation_archive_batch
            WHERE id=:id AND route_version_id=:route AND quality_revision=:quality AND row_count=:count
              AND lease_token=:token AND lease_until>clock_timestamp() AND state='VERIFIED' AND abandoned_at IS NULL
            FOR UPDATE
            """).param("id", owned.id()).param("route", owned.routeVersionId())
            .param("quality", owned.qualityRevision()).param("count", owned.rowCount())
            .param("token", owned.leaseToken()).query((rs, n) -> new Snapshot(batchOf(rs, n),
                rs.getLong("storage_revision"), revision, Location.valueOf(rs.getString("storage_state"))))
            .optional().orElseThrow(() -> new IllegalStateException("소유한 검증 완료 이관 묶음이 아니다"));
    }

    private Snapshot assertSameRetention(Snapshot prepared) {
        Snapshot current = retentionOf(prepared.batch());
        if (!current.equals(prepared)) {
            throw new IllegalStateException("파일을 읽는 동안 품질·원본 위치·작업 소유권이 바뀌었다");
        }
        return current;
    }

    private void assertPurgeReady(Snapshot snapshot) {
        var batch = snapshot.batch();
        if (snapshot.currentQuality() != batch.qualityRevision()) {
            throw new IllegalStateException("원본 검증 이후 품질 기준이 바뀌었다");
        }
        readUnchanged(batch);
        var before = clock.instant().atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL)
            .toInstant().atOffset(ZoneOffset.UTC);
        boolean ready = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM stop_demand_baseline base
                JOIN stop_demand_run run USING(route_version_id)
                WHERE base.route_version_id=:route AND base.initialized
                  AND run.phase='DONE' AND run.quality_revision=:quality
                  AND NOT EXISTS(SELECT 1 FROM evaluation_archive_member m
                    JOIN forecast_evaluation_result e USING(vehicle_observation_id,target_stop_order)
                    WHERE m.batch_id=:batch AND (e.scored_at IS NULL OR e.scored_at>=:before
                        OR e.arrived_at>=:before OR e.scored_at>run.data_until)))
              AND NOT EXISTS(SELECT 1 FROM stop_demand_rebuild_request WHERE route_version_id=:route)
              AND NOT EXISTS(SELECT 1 FROM stop_demand_rebuild_progress WHERE route_version_id=:route)
              AND NOT EXISTS(SELECT 1 FROM evaluation_archive_member m
                JOIN stop_demand_pending_sample p ON p.prediction_observation_id=m.vehicle_observation_id
                  AND p.target_stop_order=m.target_stop_order WHERE m.batch_id=:batch)
              AND NOT EXISTS(SELECT 1 FROM evaluation_archive_member m
                JOIN forecast_evaluation_pending p USING(vehicle_observation_id,target_stop_order)
                WHERE m.batch_id=:batch)
            """).param("route", batch.routeVersionId()).param("quality", snapshot.currentQuality())
            .param("batch", batch.id()).param("before", before).query(Boolean.class).single();
        if (!ready) {
            throw new IllegalStateException("오늘 자료이거나 집계·정산 처리가 끝나지 않아 삭제할 수 없다");
        }
    }

    private void assertArchivedRows(EvaluationArchiveBatch batch, List<Row> rows) {
        if (rows == null || rows.size() != batch.rowCount()) {
            throw new IllegalArgumentException("보존 원본의 건수가 묶음과 다르다");
        }
        var expected = jdbc.sql("""
            SELECT vehicle_observation_id,target_stop_order,original_sha256
            FROM evaluation_archive_member WHERE batch_id=?
            """).param(batch.id()).query((rs, n) -> java.util.Map.entry(
                new Key(rs.getLong("vehicle_observation_id"), rs.getInt("target_stop_order")),
                rs.getString("original_sha256"))).list();
        var actual = new java.util.HashMap<Key, String>();
        long bytes = 0;
        for (Row row : rows) {
            if (row == null) throw new IllegalArgumentException("보존 원본이 비어 있다");
            int size = row.originalJson().getBytes(StandardCharsets.UTF_8).length;
            bytes = Math.addExact(bytes, size);
            if (size > 64 * 1024 || bytes > 8 * 1024 * 1024
                || !digest(row.originalJson()).equals(row.sha256())
                || actual.putIfAbsent(row.key(), row.sha256()) != null) {
                throw new IllegalArgumentException("보존 원본의 크기·검증값·중복을 확인하지 못했다");
            }
        }
        if (expected.size() != batch.rowCount()
            || expected.stream().anyMatch(entry -> !entry.getValue().equals(actual.get(entry.getKey())))) {
            throw new IllegalArgumentException("보존 원본이 예약한 정산 키와 다르다");
        }
    }

    private void assertNoLiveRows(EvaluationArchiveBatch batch) {
        boolean exists = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM evaluation_archive_member m
                JOIN forecast_evaluation_result e USING(vehicle_observation_id,target_stop_order) WHERE m.batch_id=?)
            """).param(batch.id()).query(Boolean.class).single();
        if (exists) throw new IllegalStateException("삭제 기록과 달리 DB 원본이 존재한다");
    }

    private void recordLocation(Snapshot snapshot, Location location) {
        int changed = jdbc.sql("""
            UPDATE evaluation_archive_batch SET storage_state=:state,storage_revision=storage_revision+1,
                purged_at=CASE WHEN :state='PURGED' THEN clock_timestamp() ELSE purged_at END,
                restored_at=CASE WHEN :state='LIVE' THEN clock_timestamp() ELSE restored_at END
            WHERE id=:id AND storage_revision=:revision AND lease_token=:token AND lease_until>clock_timestamp()
            """).param("state", location.name()).param("id", snapshot.batch().id())
            .param("revision", snapshot.storageRevision()).param("token", snapshot.batch().leaseToken()).update();
        if (changed != 1) {
            throw new IllegalStateException("원본 위치 변경을 확정하기 전에 작업 소유권이 만료됐다");
        }
    }

    private void assertOwned(EvaluationArchiveBatch batch) {
        long revision = quality.lock(batch.routeVersionId());
        if (revision != batch.qualityRevision() || quality.anyInvestigationPending(batch.routeVersionId())) {
            throw new IllegalStateException("이관 예약 이후 품질 기준이 바뀌었다");
        }
        boolean owned = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM evaluation_archive_batch WHERE id=:id AND route_version_id=:route
              AND quality_revision=:quality AND row_count=:count AND lease_token=:token
              AND lease_until>clock_timestamp() AND abandoned_at IS NULL)
            """).param("id", batch.id()).param("route", batch.routeVersionId())
            .param("quality", batch.qualityRevision()).param("count", batch.rowCount())
            .param("token", batch.leaseToken()).query(Boolean.class).single();
        if (!owned) {
            throw new IllegalStateException("현재 실행자가 소유한 이관 작업이 아니다");
        }
    }

    private List<Row> readUnchanged(EvaluationArchiveBatch batch) {
        List<Row> rows = jdbc.sql("""
            SELECT m.vehicle_observation_id,m.target_stop_order,m.original_sha256,to_jsonb(e)::text AS original
            FROM evaluation_archive_member m
            JOIN forecast_evaluation_result e ON e.vehicle_observation_id=m.vehicle_observation_id
              AND e.target_stop_order=m.target_stop_order AND e.route_version_id=m.route_version_id
            WHERE m.batch_id=:id ORDER BY m.vehicle_observation_id,m.target_stop_order
            FOR UPDATE OF e
            """).param("id", batch.id()).query((rs, n) -> new Row(
                new Key(rs.getLong("vehicle_observation_id"), rs.getInt("target_stop_order")),
                rs.getString("original"), rs.getString("original_sha256"))).list();
        if (rows.size() != batch.rowCount() || rows.stream().anyMatch(row -> !digest(row.originalJson()).equals(row.sha256()))) {
            throw new IllegalStateException("예약한 완료 정산의 원본이 바뀌었거나 없어졌다");
        }
        return rows;
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("이관 DB 작업은 외부 트랜잭션 없이 호출해야 한다");
        }
        return transaction.execute(status -> {
            jdbc.sql("SET LOCAL statement_timeout='500ms'").update();
            jdbc.sql("SET LOCAL lock_timeout='100ms'").update();
            // 원본 JSON의 timestamptz 표현이 연결별 시간대에 따라 달라지지 않게 한다.
            jdbc.sql("SET LOCAL TIME ZONE 'UTC'").update();
            return work.get();
        });
    }

    private static EvaluationArchiveBatch batchOf(ResultSet rs, int row) throws SQLException {
        return new EvaluationArchiveBatch(rs.getObject("id", UUID.class), rs.getLong("route_version_id"),
            rs.getLong("quality_revision"), rs.getObject("lease_token", UUID.class),
            rs.getObject("lease_until", OffsetDateTime.class).toInstant(), State.valueOf(rs.getString("state")),
            rs.getInt("row_count"), rs.getString("manifest_sha256"));
    }

    private static String digest(String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
