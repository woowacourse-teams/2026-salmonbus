SELECT jsonb_build_object('id', id)::text
FROM route_version
WHERE true AND id > coalesce((?::jsonb ->> 'id')::bigint, 0)
ORDER BY id LIMIT ?
