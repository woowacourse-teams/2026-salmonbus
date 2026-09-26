WITH cursor AS (SELECT ?::jsonb AS value)
SELECT jsonb_build_object('release', release_id, 'digest', bundle_digest)::text
FROM training_model_release_exclusion, cursor
WHERE (release_id, bundle_digest) > (coalesce(value ->> 'release', ''), coalesce(value ->> 'digest', ''))
ORDER BY release_id, bundle_digest LIMIT ?
