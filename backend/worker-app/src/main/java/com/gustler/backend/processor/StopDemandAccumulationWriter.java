package com.gustler.backend.processor;

import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 한 차량의 고정된 처리 범위에서 한 페이지만 반영한다. 호출자가 다음 실행 기회를 정한다. */
@Component
public class StopDemandAccumulationWriter {
    public static final int PAGE_SIZE = 128;
    private final JdbcClient jdbc;
    private final TripQualityRepository quality;

    public StopDemandAccumulationWriter(JdbcClient jdbc, TripQualityRepository quality) {
        this.jdbc = jdbc;
        this.quality = quality;
    }

    @Transactional(timeout = 2)
    public Result apply(long version, String vehicle, long afterInputId, long inputUntilId, Instant dataUntil) {
        StopDemandRebuildWriter.limits(jdbc);
        quality.lockRoute(version);
        boolean waiting = jdbc.sql("""
            SELECT EXISTS (
                SELECT 1 FROM trip_quality_rebuild
                WHERE route_version_id = :version AND NOT completed AND vehicle_id IN ('', :vehicle)
                UNION ALL
                SELECT 1 FROM stop_demand_rebuild_request
                WHERE route_version_id = :version AND vehicle_id IN ('', :vehicle)
            )
            """).param("version", version).param("vehicle", vehicle).query(Boolean.class).single();
        if (waiting) {
            return new Result(0, 0, true, afterInputId);
        }
        Result result = jdbc.sql("""
            WITH page AS MATERIALIZED (
                SELECT * FROM stop_demand_pending_sample
                WHERE route_version_id = :version AND vehicle_id = :vehicle AND id > :afterInputId AND id <= :inputUntilId
                ORDER BY id LIMIT :limit FOR UPDATE
            ), usable AS MATERIALIZED (
                SELECT page.* FROM page
                JOIN forecast_eligible_observation source ON source.id = page.prediction_observation_id
                JOIN forecast_eligible_observation arrival ON arrival.id = page.arrival_observation_id
                  AND arrival.route_version_id = source.route_version_id
                  AND arrival.vehicle_id IS NOT DISTINCT FROM source.vehicle_id
                  AND arrival.quality_direction = source.quality_direction
            ), applied AS (
                INSERT INTO stop_demand_current_total AS total (
                    route_version_id, vehicle_id, arrived_hour_start, target_stop_order,
                    sample_count, arrival_seats_sum, net_boarding_sum
                )
                SELECT route_version_id, vehicle_id, date_trunc('hour', arrived_at, 'UTC'), target_stop_order,
                       count(*), sum(arrival_remaining_seats),
                       sum(prediction_remaining_seats - arrival_remaining_seats)
                FROM usable
                WHERE (SELECT count(*) FROM page) = (SELECT count(*) FROM usable)
                  AND scored_at <= :dataUntil
                GROUP BY route_version_id, vehicle_id, date_trunc('hour', arrived_at, 'UTC'), target_stop_order
                ON CONFLICT (route_version_id, vehicle_id, arrived_hour_start, target_stop_order)
                DO UPDATE SET sample_count = total.sample_count + EXCLUDED.sample_count,
                              arrival_seats_sum = total.arrival_seats_sum + EXCLUDED.arrival_seats_sum,
                              net_boarding_sum = total.net_boarding_sum + EXCLUDED.net_boarding_sum
                RETURNING 1
            ), registered AS (
                INSERT INTO stop_demand_vehicle(route_version_id,vehicle_id)
                SELECT :version,:vehicle WHERE EXISTS(SELECT 1 FROM applied)
                ON CONFLICT DO NOTHING RETURNING 1
            ), removed AS (
                DELETE FROM stop_demand_pending_sample pending USING page
                WHERE pending.id = page.id AND page.scored_at <= :dataUntil
                  AND EXISTS (SELECT 1 FROM applied)
                RETURNING pending.id
            )
            SELECT (SELECT count(*) FROM page) AS selected,
                   (SELECT count(*) FROM removed) AS applied,
                   (SELECT count(*) FROM page) <> (SELECT count(*) FROM usable) AS requires_rebuild,
                   COALESCE((SELECT max(id) FROM page), :afterInputId) AS next_input_id
            """).param("version", version).param("vehicle", vehicle)
            .param("afterInputId", afterInputId).param("inputUntilId", inputUntilId).param("dataUntil", dataUntil.atOffset(ZoneOffset.UTC))
            .param("limit", PAGE_SIZE)
            .query((rs, n) -> new Result(rs.getInt("selected"), rs.getInt("applied"), rs.getBoolean("requires_rebuild"), rs.getLong("next_input_id")))
            .single();
        if (result.waitingForRebuild()) {
            quality.requestStatisticsRebuild(version, vehicle);
        }
        return result;
    }

    public record Result(int selected, int applied, boolean waitingForRebuild, long nextInputId) { }
}
