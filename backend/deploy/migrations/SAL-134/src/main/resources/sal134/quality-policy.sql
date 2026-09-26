WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
INSERT INTO route_version_quality_policy(route_version_id, maximum_observation_gap_seconds, observation_gap_evidence)
SELECT id, maximum_observation_gap_seconds, observation_gap_evidence FROM route_version, bounds
WHERE id > coalesce((lower_key ->> 'id')::bigint, 0) AND id <= (upper_key ->> 'id')::bigint
ON CONFLICT DO NOTHING
