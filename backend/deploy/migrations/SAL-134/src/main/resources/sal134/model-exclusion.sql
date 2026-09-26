WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
INSERT INTO model_training_exclusion(release_id, bundle_digest, observed_model_deployment_id, calculation_version,
    observed_activated_at, final_cutover_at, statistics_baseline_count, statistics_baseline_not_after,
    classification, decision_reference, recorded_at)
SELECT release_id, bundle_digest, observed_model_deployment_id, calculation_version,
       observed_activated_at, final_cutover_at, statistics_baseline_count, statistics_baseline_not_after,
       classification, decision_reference, recorded_at
FROM training_model_release_exclusion, bounds
WHERE (release_id, bundle_digest) > (coalesce(lower_key ->> 'release', ''), coalesce(lower_key ->> 'digest', ''))
  AND (release_id, bundle_digest) <= (upper_key ->> 'release', upper_key ->> 'digest')
ON CONFLICT DO NOTHING
