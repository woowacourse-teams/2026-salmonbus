WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
INSERT INTO route_data_quality(route_id, quality_revision)
SELECT id, quality_revision FROM route, bounds
WHERE id > coalesce((lower_key ->> 'id')::bigint, 0) AND id <= (upper_key ->> 'id')::bigint
ON CONFLICT DO NOTHING
