package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveQueue;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
public class JdbcEvaluationArchiveQueue implements EvaluationArchiveQueue {
    private final JdbcClient jdbc;
    private final RouteDataQualityAccess quality;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public JdbcEvaluationArchiveQueue(JdbcClient jdbc, RouteDataQualityAccess quality, Clock clock,
        PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.quality = quality;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(2);
    }

    @Override
    public boolean available() {
        return jdbc.sql("SELECT next_attempt_at<=clock_timestamp() FROM evaluation_archive_scan WHERE id=1")
            .query(Boolean.class).single();
    }

    @Override
    public Optional<UUID> resumable(boolean deleteEnabled) {
        return inTransaction(() -> jdbc.sql("""
            SELECT id FROM evaluation_archive_batch WHERE storage_state='LIVE' AND abandoned_at IS NULL
              AND lease_until<=clock_timestamp() AND (:delete OR state='RESERVED')
            ORDER BY lease_until,id LIMIT 1
            """).param("delete", deleteEnabled).query(UUID.class).optional());
    }

    @Override
    public Optional<Candidates> next(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("탐색 페이지는 1~100개여야 한다");
        return inTransaction(() -> {
            var cursor = jdbc.sql("""
                SELECT observation_id,stop_order FROM evaluation_archive_scan
                WHERE id=1 AND next_attempt_at<=clock_timestamp() FOR UPDATE SKIP LOCKED
                """).query((rs, n) -> new Cursor(rs.getLong(1), rs.getInt(2))).optional();
            if (cursor.isEmpty()) return Optional.empty();
            var before = clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
                .atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().atOffset(ZoneOffset.UTC);
            List<Candidate> page = jdbc.sql("""
                WITH page AS MATERIALIZED (
                    SELECT vehicle_observation_id,target_stop_order,route_version_id,scored_at,arrived_at
                    FROM forecast_evaluation_result
                    WHERE (vehicle_observation_id,target_stop_order)>(:observation,:stop)
                    ORDER BY vehicle_observation_id,target_stop_order LIMIT :limit
                )
                SELECT p.*,
                    (p.scored_at<:before AND (p.arrived_at IS NULL OR p.arrived_at<:before)
                      AND NOT EXISTS(SELECT 1 FROM evaluation_archive_member m
                          WHERE m.vehicle_observation_id=p.vehicle_observation_id AND m.target_stop_order=p.target_stop_order)
                      AND EXISTS(SELECT 1 FROM stop_demand_run run JOIN stop_demand_baseline base USING(route_version_id)
                          JOIN route_version v ON v.id=run.route_version_id
                          JOIN route_data_quality q ON q.route_id=v.route_id
                          WHERE run.route_version_id=p.route_version_id AND base.initialized AND run.phase='DONE'
                            AND run.data_until>=p.scored_at AND run.quality_revision=q.quality_revision)
                      AND NOT EXISTS(SELECT 1 FROM stop_demand_rebuild_request r WHERE r.route_version_id=p.route_version_id)
                      AND NOT EXISTS(SELECT 1 FROM stop_demand_rebuild_progress r WHERE r.route_version_id=p.route_version_id)
                      AND NOT EXISTS(SELECT 1 FROM stop_demand_pending_sample s
                          WHERE s.prediction_observation_id=p.vehicle_observation_id AND s.target_stop_order=p.target_stop_order)
                    ) AS eligible
                FROM page p ORDER BY vehicle_observation_id,target_stop_order
                """).param("observation", cursor.get().observation()).param("stop", cursor.get().stop())
                .param("limit", limit).param("before", before).query((rs, n) -> new Candidate(
                    rs.getLong("route_version_id"), new Key(rs.getLong("vehicle_observation_id"), rs.getInt("target_stop_order")),
                    rs.getBoolean("eligible"))).list();
            if (page.isEmpty()) {
                jdbc.sql("UPDATE evaluation_archive_scan SET observation_id=0,stop_order=0 WHERE id=1").update();
                return Optional.empty();
            }
            // 다음 노선의 행은 건너뛰지 않는다. 다음 호출이 같은 키부터 이어 읽는다.
            long route = page.getFirst().route();
            List<Candidate> prefix = page.stream().takeWhile(row -> row.route() == route).toList();
            Key last = prefix.getLast().key();
            jdbc.sql("UPDATE evaluation_archive_scan SET observation_id=?,stop_order=? WHERE id=1")
                .params(last.observationId(), last.stopOrder()).update();
            List<Key> keys = prefix.stream().filter(Candidate::eligible).map(Candidate::key).toList();
            return keys.isEmpty() ? Optional.empty() : Optional.of(new Candidates(route, keys));
        });
    }

    @Override
    public boolean retireChanged(EvaluationArchiveBatch owned) {
        return inTransaction(() -> {
            long revision = quality.lock(owned.routeVersionId());
            boolean active = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM evaluation_archive_batch WHERE id=:id AND lease_token=:token
                    AND lease_until>clock_timestamp() AND storage_state='LIVE' AND abandoned_at IS NULL)
                """).param("id", owned.id()).param("token", owned.leaseToken()).query(Boolean.class).single();
            if (!active) return false;
            var rows = jdbc.sql("""
                SELECT m.original_sha256,to_jsonb(e)::text AS original
                FROM evaluation_archive_member m JOIN forecast_evaluation_result e
                  ON e.vehicle_observation_id=m.vehicle_observation_id AND e.target_stop_order=m.target_stop_order
                WHERE m.batch_id=? ORDER BY m.vehicle_observation_id,m.target_stop_order FOR UPDATE OF e
                """).param(owned.id()).query((rs, n) -> new Original(rs.getString(1), rs.getString(2))).list();
            // 원본이 하나라도 없으면 키 기록을 지우지 않는다.
            if (rows.size() != owned.rowCount() || rows.stream().anyMatch(row -> row.json() == null)) return false;
            if (revision == owned.qualityRevision()
                && rows.stream().allMatch(row -> digest(row.json()).equals(row.sha256()))) return false;
            jdbc.sql("DELETE FROM evaluation_archive_member WHERE batch_id=?").param(owned.id()).update();
            int changed = jdbc.sql("""
                UPDATE evaluation_archive_batch SET abandoned_at=clock_timestamp()
                WHERE id=? AND lease_token=? AND lease_until>clock_timestamp() AND storage_state='LIVE'
                  AND abandoned_at IS NULL
                """).params(owned.id(), owned.leaseToken()).update();
            if (changed != 1) throw new IllegalStateException("변경된 예약을 중단하기 전에 작업 소유권이 만료됐다");
            return true;
        });
    }

    @Override
    public void defer(EvaluationArchiveBatch owned) {
        inTransaction(() -> {
            if (owned != null) {
                quality.lock(owned.routeVersionId());
                jdbc.sql("""
                    UPDATE evaluation_archive_batch SET lease_until=clock_timestamp()+interval '60 seconds'
                    WHERE id=? AND lease_token=? AND storage_state='LIVE' AND abandoned_at IS NULL
                    """).params(owned.id(), owned.leaseToken()).update();
            }
            jdbc.sql("UPDATE evaluation_archive_scan SET next_attempt_at=clock_timestamp()+interval '60 seconds' WHERE id=1").update();
            return null;
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("이관 탐색은 바깥 트랜잭션 없이 실행해야 한다");
        }
        return transaction.execute(status -> {
            jdbc.sql("SET LOCAL statement_timeout='500ms'").update();
            jdbc.sql("SET LOCAL lock_timeout='100ms'").update();
            jdbc.sql("SET LOCAL TIME ZONE 'UTC'").update();
            return work.get();
        });
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
    private record Cursor(long observation, int stop) { }
    private record Candidate(long route, Key key, boolean eligible) { }
    private record Original(String sha256, String json) { }
}
