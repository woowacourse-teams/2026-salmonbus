WITH cursor AS (SELECT ?::jsonb AS value)
SELECT jsonb_build_object('freeze', freeze_id, 'route', route_version_id, 'calculation', calculation_version,
    'revision', revision, 'until', data_until, 'computed', computed_at)::text
FROM training_statistics_generation_exclusion, cursor
WHERE (freeze_id, route_version_id, calculation_version, revision, data_until, computed_at) >
    (coalesce((value ->> 'freeze')::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
     coalesce((value ->> 'route')::bigint, 0), coalesce(value ->> 'calculation', ''),
     coalesce((value ->> 'revision')::integer, 0), coalesce((value ->> 'until')::timestamptz, '-infinity'),
     coalesce((value ->> 'computed')::timestamptz, '-infinity'))
ORDER BY freeze_id, route_version_id, calculation_version, revision, data_until, computed_at LIMIT ?
