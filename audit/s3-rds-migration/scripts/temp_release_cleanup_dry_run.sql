\set ON_ERROR_STOP on

\if :{?formal_cutover_at}
\else
\echo 'required psql variable: formal_cutover_at'
\quit
\endif

-- Read-only evidence only. This file contains no DELETE and must not be turned
-- into one without a successful formal replacement and separate user approval.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

WITH expected AS (
    SELECT 1::bigint AS deployment_id,
           'salmonbus-d57370be9195520e'::varchar AS release_id,
           'd57370be9195520ecf3b0ef125aa3611090ed5f41ade2963c33f38d99a29e89a'::char(64)
               AS bundle_digest,
           'seat-feature-contract-v4-1-2026-09-02'::varchar AS calculation_version,
           'observed-max-capacity-v1'::varchar AS carrier_calculation_version,
           timestamptz '2026-09-02T11:55:04.729493Z' AS activated_at
), temporary_identity AS (
    SELECT COUNT(*) AS exact_rows,
           COUNT(*) FILTER (WHERE deployment.state = 'RETIRED') AS retired_rows
    FROM model_deployment deployment
    CROSS JOIN expected
    WHERE deployment.id = expected.deployment_id
      AND deployment.release_id = expected.release_id
      AND deployment.bundle_digest = expected.bundle_digest
      AND deployment.calculation_version = expected.calculation_version
      AND deployment.activated_at = expected.activated_at
), active_formal AS (
    SELECT COUNT(*) AS distinct_formal_active_rows
    FROM model_deployment deployment
    CROSS JOIN expected
    WHERE deployment.state = 'ACTIVE'
      AND deployment.id <> expected.deployment_id
      AND deployment.release_id <> expected.release_id
      AND deployment.bundle_digest <> expected.bundle_digest
      AND deployment.calculation_version <> expected.calculation_version
), temporary_generations AS (
    SELECT statistics.route_version_id,
           statistics.calculation_version,
           statistics.revision,
           statistics.data_until,
           statistics.computed_at,
           COUNT(*) AS row_count
    FROM stop_demand_statistics statistics
    CROSS JOIN expected
    WHERE statistics.calculation_version IN (
              expected.calculation_version,
              expected.carrier_calculation_version
          )
      AND statistics.computed_at >= expected.activated_at
      AND statistics.computed_at < :'formal_cutover_at'::timestamptz
    GROUP BY statistics.route_version_id,
             statistics.calculation_version,
             statistics.revision,
             statistics.data_until,
             statistics.computed_at
), temporary_generation_summary AS (
    SELECT COUNT(*) AS generations,
           COALESCE(SUM(row_count), 0) AS rows,
           MIN(revision) AS revision_min,
           MAX(revision) AS revision_max,
           MIN(data_until) AS data_until_min,
           MAX(data_until) AS data_until_max,
           MIN(computed_at) AS computed_at_min,
           MAX(computed_at) AS computed_at_max,
           COALESCE(
               jsonb_agg(
                   jsonb_build_object(
                       'route_version_id', route_version_id,
                       'calculation_version', calculation_version,
                       'revision', revision,
                       'data_until', data_until,
                       'computed_at', computed_at,
                       'row_count', row_count
                   )
                   ORDER BY route_version_id, calculation_version, revision, data_until, computed_at
               ),
               '[]'::jsonb
           ) AS exact_generation_set
    FROM temporary_generations
), pre_activation_statistics AS (
    SELECT COUNT(*) AS current_rows_before_activation_time
    FROM stop_demand_statistics statistics
    CROSS JOIN expected
    WHERE statistics.computed_at < expected.activated_at
), cleanup_targets AS (
    SELECT
        (SELECT COUNT(*)
         FROM seat_forecast forecast
         CROSS JOIN expected
         WHERE forecast.model_deployment_id = expected.deployment_id) AS temporary_forecast_rows,
        (SELECT rows FROM temporary_generation_summary) AS temporary_statistics_rows
), observation_invariant AS (
    SELECT
        (SELECT COUNT(*) FROM observation_batch) AS observation_batch_rows,
        (SELECT COUNT(*) FROM vehicle_observation) AS vehicle_observation_rows,
        (SELECT MIN(response_received_at) FROM observation_batch) AS first_response_received_at,
        (SELECT MAX(response_received_at) FROM observation_batch) AS last_response_received_at,
        (SELECT COUNT(*) FROM observation_batch WHERE forecast_completed_at IS NULL)
            AS forecast_completed_at_null_rows
)
SELECT jsonb_pretty(
    jsonb_build_object(
        'schema_version', 'salmonbus-temp-cleanup-dry-run-v1',
        'read_only', true,
        'formal_cutover_at', :'formal_cutover_at'::timestamptz,
        'user_provided_stop_demand_baseline_rows_before_activation', 0,
        'current_rows_with_computed_at_before_activation',
            pre_activation_statistics.current_rows_before_activation_time,
        'exact_temporary_identity_rows', temporary_identity.exact_rows,
        'temporary_identity_retired_rows', temporary_identity.retired_rows,
        'distinct_formal_active_rows', active_formal.distinct_formal_active_rows,
        'cleanup_target_counts', jsonb_build_object(
            'seat_forecast', cleanup_targets.temporary_forecast_rows,
            'stop_demand_statistics', cleanup_targets.temporary_statistics_rows
        ),
        'temporary_generation_summary', jsonb_build_object(
            'generations', temporary_generation_summary.generations,
            'rows', temporary_generation_summary.rows,
            'revision_min', temporary_generation_summary.revision_min,
            'revision_max', temporary_generation_summary.revision_max,
            'data_until_min', temporary_generation_summary.data_until_min,
            'data_until_max', temporary_generation_summary.data_until_max,
            'computed_at_min', temporary_generation_summary.computed_at_min,
            'computed_at_max', temporary_generation_summary.computed_at_max,
            'exact_generation_set', temporary_generation_summary.exact_generation_set,
            'delete_by_calculation_version_alone', false
        ),
        'observation_invariant', to_jsonb(observation_invariant),
        'approval_ready', (
            temporary_identity.exact_rows = 1
            AND temporary_identity.retired_rows = 1
            AND active_formal.distinct_formal_active_rows = 1
        ),
        'mutation_performed', false
    )
)
FROM temporary_identity,
     active_formal,
     temporary_generation_summary,
     pre_activation_statistics,
     cleanup_targets,
     observation_invariant;

ROLLBACK;
