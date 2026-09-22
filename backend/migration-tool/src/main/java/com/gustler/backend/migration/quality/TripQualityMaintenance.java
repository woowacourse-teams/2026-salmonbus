package com.gustler.backend.migration.quality;

import com.gustler.backend.observation.VehicleObservationsStored;
import com.gustler.backend.processor.TripQualityRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class TripQualityMaintenance {
    private final JdbcClient jdbc;
    private final TripQualityRepository quality;
    public TripQualityMaintenance(JdbcClient jdbc) { this.jdbc = jdbc; this.quality = new TripQualityRepository(jdbc); }

    /** 최근 32묶음의 표본이다. 전체 자료 수나 전체 이상 발생 수로 해석하지 않는다. */
    public Map<String, Object> preview(long version, Instant until) {
        return jdbc.sql("""
            WITH sample AS MATERIALIZED (
                SELECT id FROM observation_batch WHERE route_version_id = ? AND response_received_at <= ?
                ORDER BY response_received_at DESC, id DESC LIMIT 32
            )
            SELECT 'LAST_32_BATCHES_SAMPLE' AS scope, (SELECT count(*) FROM sample) AS sampled_batches,
                count(*) AS sampled_observations, count(*) FILTER (WHERE remaining_seats > 70) AS sampled_above_range
            FROM vehicle_observation WHERE observation_batch_id IN (SELECT id FROM sample)
            """).param(version).param(offset(until)).query().singleRow();
    }

    public Map<String, Object> applyChunk(long version, Instant until, int limit) {
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("한 번에 1~100개 관측 묶음만 처리할 수 있다"); }
        quality.lockRoute(version);
        var gap = quality.readRoute(version).maximumGap();
        Long seconds = gap == null ? null : gap.toSeconds();
        var existing = jdbc.sql("""
            SELECT last_batch_at, last_batch_id, until_at, completed, maximum_gap_seconds::bigint AS maximum_gap_seconds
            FROM trip_quality_rebuild WHERE route_version_id = ? AND vehicle_id = ''
            """).param(version).query((rs, n) -> new Progress(rs.getObject(1, OffsetDateTime.class), rs.getLong(2),
                rs.getObject(3, OffsetDateTime.class), rs.getBoolean(4), rs.getObject(5, Long.class))).list();
        if (!existing.isEmpty() && (!existing.getFirst().until().toInstant().equals(until)
            || !Objects.equals(existing.getFirst().gap(), seconds))) {
            throw new IllegalArgumentException("정리 작업의 종료 시각과 시간 기준을 변경할 수 없다");
        }
        if (existing.isEmpty()) {
            jdbc.sql("INSERT INTO trip_quality_rebuild(route_version_id, until_at, maximum_gap_seconds) VALUES (?, ?, ?)")
                .param(version).param(offset(until)).param(seconds).update();
            quality.invalidateDerivedInputs(version);
        }
        Progress cursor = existing.isEmpty() ? new Progress(offset(Instant.EPOCH), 0, offset(until), false, seconds) : existing.getFirst();
        int processed = 0;
        boolean discoveryComplete = cursor.completed();
        if (!discoveryComplete) {
            var batches = jdbc.sql("""
                SELECT id, response_received_at FROM observation_batch WHERE route_version_id = ?
                  AND response_received_at <= ? AND (response_received_at, id) > (?, ?)
                ORDER BY response_received_at, id LIMIT ?
                """).param(version).param(offset(until)).param(cursor.at()).param(cursor.id()).param(limit)
                .query((rs, n) -> new Batch(rs.getLong(1), rs.getObject(2, OffsetDateTime.class))).list();
            if (!batches.isEmpty()) {
                var anomalies = jdbc.sql("""
                    SELECT o.observation_batch_id, o.id, o.vehicle_id, o.remaining_seats
                    FROM vehicle_observation o LEFT JOIN vehicle_one_way_trip t ON t.id = o.vehicle_trip_key
                    WHERE o.observation_batch_id IN (:batches) AND o.remaining_seats > 70 AND o.vehicle_id IS NOT NULL
                      AND (t.status IS NULL OR t.status <> 'EXCLUDED')
                    ORDER BY o.observation_batch_id, o.source_row_number
                    """).param("batches", batches.stream().map(Batch::id).toList())
                    .query((rs, n) -> new Signal(rs.getLong(1), new VehicleObservationsStored.Row(rs.getLong(2), rs.getString(3), rs.getInt(4)))).list();
                Map<String, Signal> first = new LinkedHashMap<>();
                anomalies.forEach(signal -> first.putIfAbsent(signal.row().vehicleId(), signal));
                for (Signal signal : first.values()) {
                    Instant at = batches.stream().filter(b -> b.id() == signal.batch()).findFirst().orElseThrow().at().toInstant();
                    quality.observationsStored(new VehicleObservationsStored(signal.batch(), version, at, List.of(signal.row())));
                }
            }
            processed = batches.size();
            discoveryComplete = batches.size() < limit;
            var last = batches.isEmpty() ? new Batch(cursor.id(), cursor.at()) : batches.getLast();
            jdbc.sql("""
                UPDATE trip_quality_rebuild SET last_batch_at = ?, last_batch_id = ?, completed = ?, phase = ?
                WHERE route_version_id = ? AND vehicle_id = ''
                """).param(last.at()).param(last.id()).param(discoveryComplete).param(discoveryComplete ? "DONE" : "DISCOVER")
                .param(version).update();
            if (discoveryComplete) { quality.invalidateDerivedInputs(version); }
        }
        var pending = jdbc.sql("""
            SELECT vehicle_id FROM trip_quality_rebuild WHERE route_version_id = ? AND vehicle_id <> '' AND NOT completed
            ORDER BY investigated_at, vehicle_id LIMIT 1
            """).param(version).query(String.class).list();
        if (!pending.isEmpty()) { quality.investigateLocked(version, pending.getFirst()); }
        boolean investigating = jdbc.sql("SELECT EXISTS(SELECT 1 FROM trip_quality_rebuild WHERE route_version_id = ? AND vehicle_id <> '' AND NOT completed)")
            .param(version).query(Boolean.class).single();
        return Map.of("processedBatches", processed, "discoveryCompleted", discoveryComplete,
            "completed", discoveryComplete && !investigating);
    }

    public List<Map<String, Object>> status(long version) {
        return jdbc.sql("""
            SELECT vehicle_id, phase, completed, evidence_observation_id, last_batch_at, last_batch_id,
                   can_release, started_at, investigated_at
            FROM trip_quality_rebuild WHERE route_version_id = ? ORDER BY vehicle_id
            """).param(version).query().listOfRows();
    }
    private static OffsetDateTime offset(Instant at) { return at.atOffset(ZoneOffset.UTC); }
    private record Progress(OffsetDateTime at, long id, OffsetDateTime until, boolean completed, Long gap) { }
    private record Batch(long id, OffsetDateTime at) { }
    private record Signal(long batch, VehicleObservationsStored.Row row) { }
}
