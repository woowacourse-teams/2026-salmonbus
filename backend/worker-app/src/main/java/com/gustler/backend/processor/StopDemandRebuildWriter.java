package com.gustler.backend.processor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 정정 요청을 유지한 채 128행씩 재계산/교체한다. 요청이 남아 있는 동안 해당 누적과 발행은 금지한다. */
@Component
public class StopDemandRebuildWriter {
    private final JdbcClient jdbc;
    private final TripQualityRepository quality;
    private final Clock clock;

    public StopDemandRebuildWriter(JdbcClient jdbc, TripQualityRepository quality, Clock clock) {
        this.jdbc = jdbc; this.quality = quality; this.clock = clock;
    }

    @Transactional(timeout = 2)
    public boolean step(long version, String vehicle) {
        limits(jdbc);
        quality.lockRoute(version);
        if (jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM trip_quality_rebuild WHERE route_version_id=? AND NOT completed
              AND (? = '' OR vehicle_id IN ('', ?)))
            """).params(version, vehicle, vehicle).query(Boolean.class).single()) { return false; }
        var requests = jdbc.sql("SELECT request_id FROM stop_demand_rebuild_request WHERE route_version_id=? AND vehicle_id=?")
            .params(version, vehicle).query(UUID.class).list();
        if (requests.isEmpty()) { return false; }
        UUID request = requests.getFirst();
        long revision = jdbc.sql("SELECT quality_revision FROM route WHERE id=(SELECT route_id FROM route_version WHERE id=?)")
            .param(version).query(Long.class).single();
        var progress = jdbc.sql("SELECT * FROM stop_demand_rebuild_progress WHERE route_version_id=? AND vehicle_id=?")
            .params(version, vehicle).query((rs, n) -> new Progress(rs.getObject("request_id", UUID.class),
                rs.getLong("quality_revision"), rs.getObject("data_until", OffsetDateTime.class),
                rs.getLong("input_until_id"), rs.getLong("observation_until_id"), rs.getLong("cursor_id"),
                rs.getString("phase"))).list();
        if (progress.isEmpty() || !progress.getFirst().request().equals(request) || progress.getFirst().revision() != revision) {
            // 이전 시도의 중간값을 나눠 정리한 뒤 새 처리 범위를 고정한다.
            int removed = jdbc.sql("""
                DELETE FROM stop_demand_rebuild_total WHERE id IN (
                    SELECT id FROM stop_demand_rebuild_total WHERE route_version_id=? AND scope_vehicle_id=? LIMIT 128)
                """).params(version, vehicle).update();
            if (removed > 0) { return true; }
            jdbc.sql("""
                INSERT INTO stop_demand_rebuild_progress(route_version_id, vehicle_id, request_id,
                    quality_revision, data_until, input_until_id, observation_until_id, phase)
                VALUES (?, ?, ?, ?, ?, (SELECT COALESCE(max(id),0) FROM stop_demand_pending_sample),
                    (SELECT COALESCE(max(id),0) FROM vehicle_observation), 'SCAN')
                ON CONFLICT(route_version_id,vehicle_id) DO UPDATE SET request_id=EXCLUDED.request_id,
                    quality_revision=EXCLUDED.quality_revision, data_until=EXCLUDED.data_until,
                    input_until_id=EXCLUDED.input_until_id, observation_until_id=EXCLUDED.observation_until_id,
                    cursor_id=0, phase='SCAN'
                """).params(version, vehicle, request, revision, clock.instant().atOffset(ZoneOffset.UTC)).update();
            return true;
        }
        Progress p = progress.getFirst();
        switch (p.phase()) {
            case "SCAN" -> scan(version, vehicle, p);
            case "CLEAR" -> {
                int removed = jdbc.sql("""
                    DELETE FROM stop_demand_current_total WHERE (route_version_id,vehicle_id,arrived_hour_start,target_stop_order) IN (
                        SELECT route_version_id,vehicle_id,arrived_hour_start,target_stop_order FROM stop_demand_current_total
                        WHERE route_version_id=? AND (?='' OR vehicle_id=?) LIMIT 128)
                    """).params(version, vehicle, vehicle).update();
                if (removed == 0) { move(version, vehicle, "COPY", 0); }
            }
            case "COPY" -> copy(version, vehicle, p);
            case "ACK" -> acknowledge(version, vehicle, p);
            case "CLEAN" -> {
                int removed = jdbc.sql("DELETE FROM stop_demand_rebuild_total WHERE id IN (SELECT id FROM stop_demand_rebuild_total WHERE request_id=? LIMIT 128)")
                    .param(request).update();
                if (removed == 0) {
                    jdbc.sql("DELETE FROM stop_demand_rebuild_request WHERE route_version_id=? AND vehicle_id=? AND request_id=?")
                        .params(version, vehicle, request).update();
                    jdbc.sql("DELETE FROM stop_demand_rebuild_progress WHERE route_version_id=? AND vehicle_id=?")
                        .params(version, vehicle).update();
                    jdbc.sql("""
                        INSERT INTO stop_demand_baseline(route_version_id,initialized,data_until) VALUES (?, ?, ?)
                        ON CONFLICT(route_version_id) DO UPDATE SET
                          initialized=stop_demand_baseline.initialized OR EXCLUDED.initialized,
                          data_until=GREATEST(stop_demand_baseline.data_until,EXCLUDED.data_until)
                        """).params(version, vehicle.isEmpty(), p.until()).update();
                }
            }
            default -> throw new IllegalStateException("알 수 없는 통계 정정 단계: " + p.phase());
        }
        return true;
    }

    private void scan(long version, String vehicle, Progress p) {
        var page = jdbc.sql("""
            WITH page AS MATERIALIZED (
                SELECT id FROM vehicle_observation WHERE id > :cursor AND id <= :upper ORDER BY id LIMIT 128
            ), added AS (
                INSERT INTO stop_demand_rebuild_total AS total(route_version_id, scope_vehicle_id, request_id,
                    vehicle_id, arrived_hour_start, target_stop_order, sample_count, arrival_seats_sum, net_boarding_sum)
                SELECT :version, :vehicle, :request, source.vehicle_id,
                    date_trunc('hour', batch.response_received_at, 'UTC'), forecast.target_stop_order,
                    count(*), sum(forecast.seats_on_arrival), sum(source.remaining_seats-forecast.seats_on_arrival)
                FROM page JOIN forecast_eligible_observation source ON source.id=page.id
                JOIN seat_forecast forecast ON forecast.vehicle_observation_id=source.id
                JOIN forecast_eligible_observation arrival ON arrival.id=forecast.arrival_observation_id
                  AND arrival.route_version_id=source.route_version_id
                  AND arrival.vehicle_id IS NOT DISTINCT FROM source.vehicle_id
                  AND arrival.quality_direction=source.quality_direction
                JOIN observation_batch batch ON batch.id=arrival.observation_batch_id
                JOIN route_stop stop ON stop.route_version_id=forecast.route_version_id AND stop.stop_order=forecast.target_stop_order
                WHERE source.route_version_id=:version AND (:vehicle='' OR source.vehicle_id=:vehicle)
                  AND source.vehicle_id IS NOT NULL AND source.remaining_seats IS NOT NULL
                  AND forecast.scoring_state='SETTLED' AND forecast.stops_to_target=1 AND stop.boarding_allowed
                  AND forecast.scored_at<=:until
                  AND NOT EXISTS (SELECT 1 FROM stop_demand_pending_sample pending
                      WHERE pending.prediction_observation_id=source.id AND pending.target_stop_order=forecast.target_stop_order
                        AND pending.id>:inputUntil)
                GROUP BY source.vehicle_id, date_trunc('hour', batch.response_received_at, 'UTC'), forecast.target_stop_order
                ON CONFLICT(request_id,vehicle_id,arrived_hour_start,target_stop_order) DO UPDATE SET
                    sample_count=total.sample_count+EXCLUDED.sample_count,
                    arrival_seats_sum=total.arrival_seats_sum+EXCLUDED.arrival_seats_sum,
                    net_boarding_sum=total.net_boarding_sum+EXCLUDED.net_boarding_sum
                RETURNING 1
            ) SELECT count(*) AS size, COALESCE(max(id),:cursor) AS cursor FROM page
            """).param("version", version).param("vehicle", vehicle).param("request", p.request())
            .param("cursor", p.cursor()).param("upper", p.upper()).param("until", p.until()).param("inputUntil", p.inputUntil())
            .query((rs,n) -> new Page(rs.getInt("size"),rs.getLong("cursor"))).single();
        move(version, vehicle, page.size() < 128 ? "CLEAR" : "SCAN", page.size() < 128 ? 0 : page.cursor());
    }

    private void copy(long version, String vehicle, Progress p) {
        var page = jdbc.sql("""
            WITH page AS MATERIALIZED (
                SELECT * FROM stop_demand_rebuild_total WHERE request_id=:request AND id>:cursor ORDER BY id LIMIT 128
            ), copied AS (
                INSERT INTO stop_demand_current_total(route_version_id,vehicle_id,arrived_hour_start,target_stop_order,
                    sample_count,arrival_seats_sum,net_boarding_sum)
                SELECT route_version_id,vehicle_id,arrived_hour_start,target_stop_order,sample_count,arrival_seats_sum,net_boarding_sum FROM page
                ON CONFLICT(route_version_id,vehicle_id,arrived_hour_start,target_stop_order) DO UPDATE SET
                    sample_count=EXCLUDED.sample_count,arrival_seats_sum=EXCLUDED.arrival_seats_sum,net_boarding_sum=EXCLUDED.net_boarding_sum
                RETURNING route_version_id,vehicle_id
            ), registered AS (
                INSERT INTO stop_demand_vehicle SELECT DISTINCT route_version_id,vehicle_id FROM copied
                ON CONFLICT DO NOTHING RETURNING 1
            ) SELECT count(*) AS size, COALESCE(max(id),:cursor) AS cursor FROM page
            """).param("request", p.request()).param("cursor", p.cursor())
            .query((rs,n) -> new Page(rs.getInt("size"),rs.getLong("cursor"))).single();
        move(version, vehicle, page.size() < 128 ? "ACK" : "COPY", page.size() < 128 ? 0 : page.cursor());
    }

    private void acknowledge(long version, String vehicle, Progress p) {
        var page = jdbc.sql("""
            WITH page AS MATERIALIZED (
                SELECT id,scored_at FROM stop_demand_pending_sample
                WHERE route_version_id=:version AND (:vehicle='' OR vehicle_id=:vehicle)
                  AND id>:cursor AND id<=:upper ORDER BY id LIMIT 128
            ), removed AS (
                DELETE FROM stop_demand_pending_sample pending USING page
                WHERE pending.id=page.id AND page.scored_at<=:until RETURNING pending.id
            ) SELECT count(*) AS size, COALESCE(max(id),:cursor) AS cursor FROM page
            """).param("version",version).param("vehicle",vehicle).param("cursor",p.cursor())
            .param("upper",p.inputUntil()).param("until",p.until())
            .query((rs,n) -> new Page(rs.getInt("size"),rs.getLong("cursor"))).single();
        move(version,vehicle,page.size()<128 ? "CLEAN" : "ACK",page.size()<128 ? 0 : page.cursor());
    }

    private void move(long version, String vehicle, String phase, long cursor) {
        jdbc.sql("UPDATE stop_demand_rebuild_progress SET phase=?,cursor_id=? WHERE route_version_id=? AND vehicle_id=?")
            .params(phase,cursor,version,vehicle).update();
    }

    static void limits(JdbcClient jdbc) {
        jdbc.sql("""
            SELECT set_config('statement_timeout','500ms',true),set_config('lock_timeout','100ms',true),
                   set_config('work_mem','1MB',true),set_config('max_parallel_workers_per_gather','0',true)
            """).query().singleRow();
    }
    private record Progress(UUID request,long revision,OffsetDateTime until,long inputUntil,long upper,long cursor,String phase) { }
    private record Page(int size,long cursor) { }
}
