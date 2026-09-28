WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
INSERT INTO demand_statistics_version(route_version_id, calculation_version, revision,
    data_until, computed_at, quality_revision, cell_count, input_checkpoint)
SELECT route_version_id, calculation_version, revision,
       min(data_until), min(computed_at), min(quality_revision), count(*)::integer, NULL::bigint
FROM stop_demand_statistics, bounds
WHERE (route_version_id, calculation_version, revision) >
    (coalesce((lower_key ->> 'route')::bigint, 0), coalesce(lower_key ->> 'calculation', ''),
     coalesce((lower_key ->> 'revision')::integer, 0))
  AND (route_version_id, calculation_version, revision) <=
    ((upper_key ->> 'route')::bigint, upper_key ->> 'calculation', (upper_key ->> 'revision')::integer)
GROUP BY route_version_id, calculation_version, revision
UNION ALL
SELECT publication.route_version_id, publication.calculation_version, publication.revision,
       publication.data_until, publication.computed_at, publication.quality_revision, 0, NULL::bigint
FROM stop_demand_publication publication, bounds
WHERE (publication.route_version_id, publication.calculation_version, publication.revision) >
    (coalesce((lower_key ->> 'route')::bigint, 0), coalesce(lower_key ->> 'calculation', ''),
     coalesce((lower_key ->> 'revision')::integer, 0))
  AND (publication.route_version_id, publication.calculation_version, publication.revision) <=
    ((upper_key ->> 'route')::bigint, upper_key ->> 'calculation', (upper_key ->> 'revision')::integer)
  AND NOT EXISTS (SELECT 1 FROM stop_demand_statistics cell
      WHERE cell.route_version_id = publication.route_version_id
        AND cell.calculation_version = publication.calculation_version
        AND cell.revision = publication.revision)
ON CONFLICT DO NOTHING
