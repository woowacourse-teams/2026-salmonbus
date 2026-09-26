SELECT jsonb_build_object('id', id)::text
FROM model_active_slot
WHERE true AND id > coalesce((?::jsonb ->> 'id')::bigint, 0)
ORDER BY id LIMIT ?
