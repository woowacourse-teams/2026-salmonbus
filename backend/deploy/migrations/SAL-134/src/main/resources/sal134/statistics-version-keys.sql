WITH cursor AS (SELECT ?::jsonb AS value)
SELECT jsonb_build_object('route', route_version_id, 'calculation', calculation_version, 'revision', revision)::text
FROM stop_demand_statistics, cursor
WHERE (route_version_id, calculation_version, revision) >
    (coalesce((value ->> 'route')::bigint, 0), coalesce(value ->> 'calculation', ''), coalesce((value ->> 'revision')::integer, 0))
GROUP BY route_version_id, calculation_version, revision
ORDER BY route_version_id, calculation_version, revision LIMIT ?
