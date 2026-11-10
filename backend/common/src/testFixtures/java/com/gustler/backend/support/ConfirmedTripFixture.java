package com.gustler.backend.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** 같은 차량의 관측에 테스트용 편도 식별자를 설정한다. */
public final class ConfirmedTripFixture {
    private ConfirmedTripFixture() {
    }

    public static long include(JdbcClient jdbc, final long observationId) {
        jdbc.sql("""
            UPDATE vehicle_observation target
            SET vehicle_trip_key = (
                SELECT CAST(min(observation.id) AS text) FROM vehicle_observation observation
                WHERE observation.route_version_id = target.route_version_id
                  AND observation.vehicle_id IS NOT DISTINCT FROM target.vehicle_id
            )
            WHERE target.id = ?
            """).param(observationId).update();
        // 업무 writer를 거치지 않는 fixture도 수집 시 확정한 정원 이력을 제공한다.
        jdbc.sql("""
            INSERT INTO vehicle_capacity_history(source_id,vehicle_id,evidence_at,evidence_batch_id,capacity)
            SELECT r.source_id,o.vehicle_id,b.response_received_at,b.id,GREATEST(o.remaining_seats,COALESCE((
                SELECT max(h.capacity) FROM vehicle_capacity_history h
                WHERE h.source_id=r.source_id AND h.vehicle_id=o.vehicle_id
                  AND (h.evidence_at,h.evidence_batch_id)<=(b.response_received_at,b.id)),0))
            FROM vehicle_observation o JOIN observation_batch b ON b.id=o.observation_batch_id
            JOIN route_version v ON v.id=o.route_version_id JOIN route r ON r.id=v.route_id
            WHERE o.id=? AND o.vehicle_id IS NOT NULL AND o.remaining_seats BETWEEN 1 AND 70
              AND b.response_received_at IS NOT NULL ON CONFLICT DO NOTHING
            """).param(observationId).update();
        return observationId;
    }
}
