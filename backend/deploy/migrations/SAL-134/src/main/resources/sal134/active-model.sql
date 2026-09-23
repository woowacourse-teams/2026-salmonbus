WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
UPDATE model_active_slot
SET model_deployment_id = (SELECT id FROM model_deployment WHERE state = 'ACTIVE'),
    version = CASE WHEN EXISTS (SELECT 1 FROM model_deployment WHERE state = 'ACTIVE') THEN 1 ELSE 0 END
FROM bounds
WHERE id > coalesce((lower_key ->> 'id')::bigint, 0) AND id <= (upper_key ->> 'id')::bigint
