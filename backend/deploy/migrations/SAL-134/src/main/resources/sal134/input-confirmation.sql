WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
UPDATE observation_batch batch SET input_confirmed_at = batch.forecast_completed_at
FROM bounds
WHERE batch.id > coalesce((lower_key ->> 'id')::bigint, 0)
  AND batch.id <= (upper_key ->> 'id')::bigint
  AND batch.input_confirmed_at IS NULL
  AND batch.forecast_completed_at IS NOT NULL
