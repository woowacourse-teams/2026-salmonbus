SET LOCAL lock_timeout = '5s';

-- 기존 Worker와 수동 조사를 중지한 뒤 적용한다. 진행 중인 판정을 영구 제외로 확정하지 않는다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM trip_quality_rebuild WHERE NOT completed) THEN
        RAISE EXCEPTION 'Finish pending historical trip quality investigations before migrating';
    END IF;
END $$;

WITH historical AS MATERIALIZED (
    SELECT observation.id, assignment.trip_id, trip.status,
           completed.route_version_id IS NOT NULL AS confirmed
    FROM observation_trip_assignment assignment
    JOIN vehicle_observation observation ON observation.id = assignment.observation_id
    JOIN vehicle_one_way_trip trip ON trip.id = assignment.trip_id
    LEFT JOIN trip_quality_rebuild completed
      ON completed.route_version_id = observation.route_version_id AND completed.vehicle_id = observation.vehicle_id
      AND completed.completed AND completed.phase = 'DONE'
    UNION ALL
    SELECT observation.id, NULL, trip.status, completed.route_version_id IS NOT NULL
    FROM vehicle_observation observation
    JOIN vehicle_one_way_trip trip ON trip.id = observation.vehicle_trip_key AND trip.status <> 'ELIGIBLE'
    LEFT JOIN trip_quality_rebuild completed
      ON completed.route_version_id = observation.route_version_id AND completed.vehicle_id = observation.vehicle_id
      AND completed.completed AND completed.phase = 'DONE'
    WHERE NOT EXISTS (SELECT 1 FROM observation_trip_assignment assignment WHERE assignment.observation_id = observation.id)
)
UPDATE vehicle_observation observation
SET historical_trip_id = historical.trip_id,
    historical_exclusion = CASE
        WHEN historical.status = 'EXCLUDED' AND historical.confirmed THEN 'CONFIRMED'
        WHEN historical.status <> 'ELIGIBLE' THEN 'INELIGIBLE'
    END
FROM historical
WHERE observation.id = historical.id;

UPDATE vehicle_observation
SET historical_exclusion = 'INELIGIBLE'
WHERE remaining_seats > 70 AND historical_exclusion IS NULL;

-- 과거 조사와 학습용 뷰는 보존한다. 서비스는 관측 행에 확정된 값만 읽는다.
CREATE VIEW operational_observation AS
SELECT observation.id,
       observation.observation_batch_id,
       observation.route_version_id,
       observation.source_row_number,
       observation.vehicle_id,
       COALESCE(observation.historical_trip_id, observation.vehicle_trip_key)::varchar(120) AS vehicle_trip_key,
       observation.plate_number,
       observation.stop_order,
       observation.stop_id,
       observation.running_state,
       observation.remaining_seats,
       observation.crowd_level,
       observation.vehicle_type,
       observation.route_type,
       observation.tagless,
       observation.seat_unknown_reason,
       observation.passed_stop_order,
       CASE WHEN version.turn_sequence IS NOT NULL AND (
           observation.stop_order > version.turn_sequence
           OR (observation.stop_order = version.turn_sequence AND observation.running_state = 2)
           OR (observation.stop_order = 1 AND observation.running_state = 1))
           THEN 1 ELSE 0 END::bigint AS quality_direction,
       (observation.historical_exclusion IS NULL) AS forecast_eligible
FROM vehicle_observation observation
LEFT JOIN route_version version ON version.id = observation.route_version_id;

CREATE VIEW operational_eligible_observation AS
SELECT * FROM operational_observation WHERE forecast_eligible;

CREATE VIEW one_stop_operational_seat_forecast AS
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.route_version_id,
       forecast.stops_to_target, forecast.model_deployment_id, forecast.demand_statistics_revision,
       forecast.seat_full_chance_raw, forecast.seat_full_chance, forecast.expected_seats, forecast.generated_at,
       evaluation.scoring_state, evaluation.arrival_observation_id, evaluation.seats_on_arrival,
       evaluation.scored_at, forecast.quality_revision
FROM seat_forecast forecast
JOIN forecast_evaluation_result evaluation USING (vehicle_observation_id, target_stop_order)
JOIN operational_eligible_observation source ON source.id = forecast.vehicle_observation_id
LEFT JOIN operational_eligible_observation arrival ON arrival.id = evaluation.arrival_observation_id
WHERE forecast.stops_to_target = 1
AND (evaluation.arrival_observation_id IS NULL OR (
    arrival.id IS NOT NULL
    AND evaluation.arrival_route_version_id = source.route_version_id
    AND evaluation.arrival_vehicle_id IS NOT DISTINCT FROM source.vehicle_id
    AND evaluation.arrival_quality_direction = source.quality_direction
));
