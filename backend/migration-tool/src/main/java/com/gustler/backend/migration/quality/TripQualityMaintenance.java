package com.gustler.backend.migration.quality;

import com.gustler.backend.processor.TripQualityRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 호출자가 transaction을 연다. 원본/예측/통계를 삭제하지 않고 판정과 재사용 상태만 바꾼다. */
public final class TripQualityMaintenance {
    private final JdbcClient jdbc;
    private final TripQualityRepository quality;

    public TripQualityMaintenance(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.quality = new TripQualityRepository(jdbc);
    }

    /** 읽기 전용 사전 영향 조사. 편도 판정 결과는 apply 후 status에서 별도로 확인한다. */
    public Map<String, Object> preview(long version, Instant until) {
        return jdbc.sql("""
            SELECT count(*) AS observations,
                   count(*) FILTER (WHERE o.remaining_seats > 70) AS above_range,
                   count(*) FILTER (WHERE o.vehicle_id IS NULL) AS vehicle_unknown,
                   count(*) FILTER (WHERE t.id IS NULL) AS unassessed,
                   (SELECT count(*) FROM seat_forecast WHERE route_version_id = :version) AS stored_forecasts,
                   (SELECT count(*) FROM stop_demand_statistics s JOIN route_version v ON v.id = s.route_version_id
                    WHERE v.route_id = (SELECT route_id FROM route_version WHERE id = :version)) AS route_statistics,
                   (SELECT count(*) FROM seat_forecast f JOIN route_version v ON v.id = f.route_version_id
                    WHERE v.route_id = (SELECT route_id FROM route_version WHERE id = :version)) AS route_forecasts_for_review
            FROM vehicle_observation o JOIN observation_batch b ON b.id = o.observation_batch_id
            LEFT JOIN vehicle_one_way_trip t ON t.id = o.vehicle_trip_key
            WHERE o.route_version_id = :version AND b.response_received_at <= :until
            """).param("version", version).param("until", offset(until)).query().singleRow();
    }

    /** 최대 100개 batch씩 처리한다. 같은 until로 재실행하면 커서부터 계속하고 완료 후에는 변경하지 않는다. */
    public Map<String, Object> applyChunk(long version, Instant until, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("한 번에 1~100개 관측 묶음만 처리할 수 있다");
        }
        quality.lockRoute(version);
        var maximumGap = quality.readRoute(version).maximumGap();
        Long gapSeconds = maximumGap == null ? null : maximumGap.toSeconds();
        List<Progress> existing = jdbc.sql("SELECT last_batch_at, last_batch_id, until_at, completed, maximum_gap_seconds::bigint AS maximum_gap_seconds FROM trip_quality_rebuild WHERE route_version_id = ?")
            .param(version).query((rs, n) -> new Progress(rs.getObject("last_batch_at", OffsetDateTime.class),
                rs.getLong("last_batch_id"), rs.getObject("until_at", OffsetDateTime.class), rs.getBoolean("completed"), rs.getObject("maximum_gap_seconds", Long.class))).list();
        if (!existing.isEmpty() && !existing.getFirst().until().toInstant().equals(until)) {
            throw new IllegalArgumentException("진행 중이거나 완료한 정리 작업의 until은 변경할 수 없다");
        }
        if (!existing.isEmpty() && !Objects.equals(existing.getFirst().gapSeconds(), gapSeconds)) {
            throw new IllegalArgumentException("정리 작업 중 관측 연결 시간 기준을 변경할 수 없다");
        }
        if (!existing.isEmpty() && existing.getFirst().completed()) {
            return Map.of("processedBatches", 0, "completed", true);
        }
        if (existing.isEmpty()) {
            jdbc.sql("INSERT INTO trip_quality_rebuild(route_version_id, until_at, maximum_gap_seconds) VALUES (?, ?, ?)")
                .param(version).param(offset(until)).param(gapSeconds).update();
            quality.invalidateDerivedInputs(version);
        }
        Progress progress = existing.isEmpty() ? new Progress(null, 0, offset(until), false, gapSeconds) : existing.getFirst();
        List<Batch> batches = jdbc.sql("""
            SELECT id, response_received_at FROM observation_batch
            WHERE route_version_id = :version AND response_received_at <= :until
              AND response_received_at IS NOT NULL
              AND (:afterId = 0 OR (response_received_at, id) > (:afterAt, :afterId))
            ORDER BY response_received_at, id LIMIT :limit
            """).param("version", version).param("until", offset(until))
            .param("afterId", progress.id()).param("afterAt", progress.at() == null ? offset(Instant.EPOCH) : progress.at())
            .param("limit", limit).query((rs, n) -> new Batch(rs.getLong("id"), rs.getObject("response_received_at", OffsetDateTime.class))).list();
        for (Batch batch : batches) {
            quality.assessBatchLocked(batch.id(), version, true);
            jdbc.sql("UPDATE trip_quality_rebuild SET last_batch_at = ?, last_batch_id = ? WHERE route_version_id = ?")
                .param(batch.at()).param(batch.id()).param(version).update();
        }
        boolean completed = batches.size() < limit;
        if (completed) {
            // 시작 전 실시간 판정 중 until 뒤의 관측은 옛 연결을 유지할 수 없으므로 보류한다.
            jdbc.sql("""
                UPDATE vehicle_observation o SET vehicle_trip_key = NULL FROM observation_batch b
                WHERE o.vehicle_trip_key IS NOT NULL AND b.id = o.observation_batch_id
                  AND o.route_version_id = ? AND b.response_received_at > ?
                """).param(version).param(offset(until)).update();
            jdbc.sql("UPDATE trip_quality_rebuild SET completed = true WHERE route_version_id = ?").param(version).update();
            quality.invalidateDerivedInputs(version);
        }
        return Map.of("processedBatches", batches.size(), "completed", completed);
    }

    public List<Map<String, Object>> status(long version) {
        return jdbc.sql("""
            SELECT t.status, count(DISTINCT t.id) trips, count(*) observations
            FROM vehicle_observation o JOIN vehicle_one_way_trip t ON t.id = o.vehicle_trip_key
            WHERE t.route_version_id = ? GROUP BY t.status ORDER BY t.status
            """).param(version).query().listOfRows();
    }

    private static OffsetDateTime offset(Instant at) { return at.atOffset(ZoneOffset.UTC); }
    private record Progress(OffsetDateTime at, long id, OffsetDateTime until, boolean completed, Long gapSeconds) { }
    private record Batch(long id, OffsetDateTime at) { }
}
