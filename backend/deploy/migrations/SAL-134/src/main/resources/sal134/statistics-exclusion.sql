WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
INSERT INTO statistics_training_exclusion(freeze_id, release_id, bundle_digest, route_version_id,
    calculation_version, revision, data_until, computed_at, frozen_cell_count, frozen_at)
SELECT freeze_id, release_id, bundle_digest, route_version_id,
       calculation_version, revision, data_until, computed_at, frozen_cell_count, frozen_at
FROM training_statistics_generation_exclusion, bounds
WHERE (freeze_id, route_version_id, calculation_version, revision, data_until, computed_at) >
    (coalesce((lower_key ->> 'freeze')::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
     coalesce((lower_key ->> 'route')::bigint, 0), coalesce(lower_key ->> 'calculation', ''),
     coalesce((lower_key ->> 'revision')::integer, 0), coalesce((lower_key ->> 'until')::timestamptz, '-infinity'),
     coalesce((lower_key ->> 'computed')::timestamptz, '-infinity'))
  AND (freeze_id, route_version_id, calculation_version, revision, data_until, computed_at) <=
    ((upper_key ->> 'freeze')::uuid, (upper_key ->> 'route')::bigint, upper_key ->> 'calculation',
     (upper_key ->> 'revision')::integer, (upper_key ->> 'until')::timestamptz, (upper_key ->> 'computed')::timestamptz)
ON CONFLICT DO NOTHING
