WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
INSERT INTO forecast_publication(source_batch_id, source_attempt_number, route_version_id,
    model_deployment_id, demand_statistics_revision, quality_revision, observed_at, generated_at,
    published_at, prediction_count, provenance)
SELECT batch.id, batch.attempt_number, batch.route_version_id,
       min(forecast.model_deployment_id), min(forecast.demand_statistics_revision), min(forecast.quality_revision),
       batch.response_received_at, min(forecast.generated_at), batch.forecast_completed_at,
       count(forecast.vehicle_observation_id),
       CASE WHEN count(forecast.vehicle_observation_id) = 0 THEN 'LEGACY_UNKNOWN' ELSE 'RECORDED' END
FROM observation_batch batch
LEFT JOIN vehicle_observation observation ON observation.observation_batch_id = batch.id
LEFT JOIN seat_forecast forecast ON forecast.vehicle_observation_id = observation.id
CROSS JOIN bounds
WHERE batch.id > coalesce((lower_key ->> 'id')::bigint, 0)
  AND batch.id <= (upper_key ->> 'id')::bigint
  AND batch.forecast_completed_at IS NOT NULL
GROUP BY batch.id
ON CONFLICT (source_batch_id) DO NOTHING
