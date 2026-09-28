WITH cursor AS (SELECT ?::jsonb AS value)
SELECT jsonb_build_object('route', route_version_id, 'calculation', calculation_version, 'revision', revision)::text
FROM (
    SELECT route_version_id, calculation_version, revision FROM stop_demand_statistics
    UNION
    SELECT route_version_id, calculation_version, revision FROM stop_demand_publication
) generations, cursor
WHERE (route_version_id, calculation_version, revision) >
    (coalesce((value ->> 'route')::bigint, 0), coalesce(value ->> 'calculation', ''), coalesce((value ->> 'revision')::integer, 0))
ORDER BY route_version_id, calculation_version, revision LIMIT ?
