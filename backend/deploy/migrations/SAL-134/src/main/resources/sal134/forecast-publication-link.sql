WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
UPDATE seat_forecast forecast SET publication_id = publication.id
FROM vehicle_observation observation
JOIN forecast_publication publication ON publication.source_batch_id = observation.observation_batch_id
CROSS JOIN bounds
WHERE forecast.vehicle_observation_id = observation.id
  AND (forecast.vehicle_observation_id, forecast.target_stop_order) >
    (coalesce((lower_key ->> 'id')::bigint, 0), coalesce((lower_key ->> 'stop')::integer, 0))
  AND (forecast.vehicle_observation_id, forecast.target_stop_order) <=
    ((upper_key ->> 'id')::bigint, (upper_key ->> 'stop')::integer)
  AND forecast.publication_id IS NULL
