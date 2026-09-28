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
SELECT count(*) FROM stop_demand_publication publication
JOIN stop_demand_statistics cell ON cell.route_version_id = publication.route_version_id
    AND cell.calculation_version = publication.calculation_version AND cell.revision = publication.revision
WHERE cell.data_until <> publication.data_until OR cell.computed_at <> publication.computed_at
   OR cell.quality_revision <> publication.quality_revision
