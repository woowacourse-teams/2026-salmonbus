package com.gustler.backend.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** 편도 판정 자체를 다루지 않는 저장소/API 테스트에서 '확인된 같은 편도'라는 전제를 명시한다. */
public final class ConfirmedTripFixture {
    private ConfirmedTripFixture() { }

    public static long include(JdbcClient jdbc, long observationId) {
        long trip = jdbc.sql("""
            SELECT min(o.id) FROM vehicle_observation o JOIN vehicle_observation target
              ON o.route_version_id = target.route_version_id
             AND o.vehicle_id IS NOT DISTINCT FROM target.vehicle_id
            WHERE target.id = ?
            """).param(observationId).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO vehicle_one_way_trip(id, start_observation_id, route_version_id, vehicle_id, status, boundary, rule_version)
            SELECT CAST(? AS text), ?, route_version_id, vehicle_id, 'ELIGIBLE', 'DEPARTURE', 'test-confirmed-trip'
            FROM vehicle_observation WHERE id = ? ON CONFLICT DO NOTHING
            """).param(trip).param(trip).param(observationId).update();
        jdbc.sql("UPDATE vehicle_observation SET vehicle_trip_key = ? WHERE id = ?")
            .param(Long.toString(trip)).param(observationId).update();
        return observationId;
    }
}
