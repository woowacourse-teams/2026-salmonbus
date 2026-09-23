WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
INSERT INTO demand_statistics_version(route_version_id, calculation_version, revision,
    data_until, computed_at, quality_revision, cell_count, input_checkpoint)
SELECT route_version_id, calculation_version, revision,
       min(data_until), min(computed_at), min(quality_revision), count(*), NULL
FROM stop_demand_statistics, bounds
WHERE (route_version_id, calculation_version, revision) >
    (coalesce((lower_key ->> 'route')::bigint, 0), coalesce(lower_key ->> 'calculation', ''),
     coalesce((lower_key ->> 'revision')::integer, 0))
  AND (route_version_id, calculation_version, revision) <=
    ((upper_key ->> 'route')::bigint, upper_key ->> 'calculation', (upper_key ->> 'revision')::integer)
GROUP BY route_version_id, calculation_version, revision
ON CONFLICT DO NOTHING
