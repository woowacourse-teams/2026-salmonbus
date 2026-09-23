-- 한 발행에 모델·계산 시각·통계·품질 버전을 섞어서 기록하지 않는다.
SELECT count(*) FROM (
    SELECT observation.observation_batch_id
    FROM seat_forecast forecast JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
    GROUP BY observation.observation_batch_id
    HAVING count(DISTINCT forecast.model_deployment_id) <> 1
        OR count(DISTINCT forecast.generated_at) <> 1
        OR count(DISTINCT forecast.demand_statistics_revision) <> 1
        OR count(DISTINCT forecast.quality_revision) <> 1
) mixed
-- next-statement
SELECT count(*) FROM seat_forecast forecast
JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
JOIN observation_batch batch ON batch.id = observation.observation_batch_id
WHERE batch.forecast_completed_at IS NULL
-- next-statement
SELECT count(*) FROM observation_batch
WHERE forecast_completed_at IS NOT NULL
  AND (outcome NOT IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY') OR response_received_at IS NULL OR attempt_number < 1)
-- next-statement
SELECT count(*) FROM (
    SELECT route_version_id, calculation_version, revision
    FROM stop_demand_statistics
    GROUP BY route_version_id, calculation_version, revision
    HAVING count(DISTINCT data_until) <> 1 OR count(DISTINCT computed_at) <> 1 OR count(DISTINCT quality_revision) <> 1
) mixed
-- next-statement
SELECT count(*) FROM vehicle_observation observation
JOIN vehicle_one_way_trip trip ON trip.id = observation.vehicle_trip_key
WHERE trip.route_version_id <> observation.route_version_id
   OR trip.vehicle_id IS DISTINCT FROM observation.vehicle_id
-- next-statement
SELECT count(*) FROM seat_forecast forecast
LEFT JOIN vehicle_observation arrival ON arrival.id = forecast.arrival_observation_id
LEFT JOIN observation_batch batch ON batch.id = arrival.observation_batch_id
WHERE forecast.arrival_observation_id IS NOT NULL AND (arrival.id IS NULL OR batch.response_received_at IS NULL)
-- next-statement
-- 품질 판정과 조사 커서가 참조하는 관측을 찾을 수 없으면 전환을 중단한다.
WITH quality_references AS (
    SELECT reference.observation_id, trip.assessed_at AS referenced_at
    FROM vehicle_one_way_trip trip
    CROSS JOIN LATERAL (VALUES (trip.start_observation_id), (trip.evidence_observation_id)) reference(observation_id)
    WHERE reference.observation_id IS NOT NULL
    UNION ALL
    SELECT reference.observation_id, investigation.investigated_at AS referenced_at
    FROM trip_quality_rebuild investigation
    CROSS JOIN LATERAL (VALUES (investigation.anchor_observation_id), (investigation.previous_observation_id),
        (investigation.boundary_candidate_observation_id), (investigation.evidence_observation_id)) reference(observation_id)
    WHERE reference.observation_id IS NOT NULL
), missing AS (
    SELECT DISTINCT reference.observation_id
    FROM quality_references reference
    LEFT JOIN vehicle_observation observation ON observation.id = reference.observation_id
    WHERE observation.id IS NULL
)
SELECT count(*), (SELECT string_agg(observation_id::text, ', ' ORDER BY observation_id)
    FROM (SELECT observation_id FROM missing ORDER BY observation_id LIMIT 10) examples)
FROM missing
