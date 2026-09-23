SELECT jsonb_build_object('id', id)::text
FROM observation_batch
WHERE forecast_completed_at IS NOT NULL AND id > coalesce((?::jsonb ->> 'id')::bigint, 0)
ORDER BY id LIMIT ?
