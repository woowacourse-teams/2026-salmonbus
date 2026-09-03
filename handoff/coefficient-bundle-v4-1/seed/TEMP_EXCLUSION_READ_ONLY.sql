BEGIN TRANSACTION READ ONLY;

SELECT current_setting('transaction_read_only') AS must_be_on;

-- Invoke psql with a concrete, reviewed UTC cutover instant:
--   psql -v final_cutover_at='YYYY-MM-DDTHH:MM:SS.ffffffZ' -f TEMP_EXCLUSION_READ_ONLY.sql
-- If final_cutover_at is absent, psql leaves the token unresolved and PostgreSQL
-- fails closed instead of measuring an open-ended deletion target.

-- Exact lineage row must remain for audit. This query fails the human review if
-- any identifier differs from the approved TEMPORARY_SMOKE_ONLY record.
SELECT id, release_id, bundle_digest, calculation_version, state, activated_at, retired_at
FROM model_deployment
WHERE id = 1
  AND release_id = 'salmonbus-d57370be9195520e'
  AND bundle_digest = 'd57370be9195520ecf3b0ef125aa3611090ed5f41ade2963c33f38d99a29e89a'
  AND calculation_version = 'seat-feature-contract-v4-1-2026-09-02'
  AND activated_at = TIMESTAMPTZ '2026-09-02T11:55:04.729493Z';

-- Read-only deletion-target counts. Do not turn these into DELETE statements
-- until the formal replacement succeeds and the user separately approves the
-- exact targets and counts.
SELECT count(*) AS temporary_forecast_rows
FROM seat_forecast
WHERE model_deployment_id = 1;

SELECT count(*) AS temporary_calculation_statistics_rows
FROM stop_demand_statistics
WHERE calculation_version = 'seat-feature-contract-v4-1-2026-09-02';

-- Baseline evidence supplied at activation was zero. Re-measure it without
-- deleting anything; a non-zero result is a lineage conflict requiring review.
SELECT count(*) AS statistics_before_temp_activation_must_be_zero
FROM stop_demand_statistics
WHERE computed_at < TIMESTAMPTZ '2026-09-02T11:55:04.729493Z';

-- Exact generation set contaminated by the temp carrier. The current job writes
-- observed-max-capacity-v1 regardless of the temp bundle's own calculation version.
-- Persist the reviewed result as a detached receipt outside the DB; any later
-- approved cleanup must match all five identity fields, never version alone.
SELECT route_version_id,
       calculation_version,
       revision,
       data_until,
       computed_at,
       count(*) AS cell_rows
FROM stop_demand_statistics
WHERE computed_at >= TIMESTAMPTZ '2026-09-02T11:55:04.729493Z'
  AND computed_at < :'final_cutover_at'::timestamptz
  AND calculation_version IN (
      'seat-feature-contract-v4-1-2026-09-02',
      'observed-max-capacity-v1'
  )
GROUP BY route_version_id, calculation_version, revision, data_until, computed_at
ORDER BY route_version_id, calculation_version, revision, data_until, computed_at;

-- These rows are expressly retained. forecast_completed_at is measured only;
-- it must never be reset to NULL as part of replacement cleanup.
SELECT count(DISTINCT observation_batch.id) AS retained_observation_batches,
       count(vehicle_observation.id) AS retained_vehicle_observations,
       count(DISTINCT observation_batch.id)
           FILTER (WHERE observation_batch.forecast_completed_at IS NOT NULL)
           AS completed_markers_retained
FROM seat_forecast
JOIN vehicle_observation
  ON vehicle_observation.id = seat_forecast.vehicle_observation_id
JOIN observation_batch
  ON observation_batch.id = vehicle_observation.observation_batch_id
WHERE seat_forecast.model_deployment_id = 1;

-- Canonical anti-contamination selection for any future RDS-backed measurement.
-- The current trainer does not execute this query: it reads the S3 closure ending
-- 2026-09-01. This is the required guard if live observations are measured later.
WITH eligible_forecast AS (
    SELECT forecast.*
    FROM seat_forecast forecast
    JOIN model_deployment deployment
      ON deployment.id = forecast.model_deployment_id
    WHERE NOT (
        deployment.id = 1
        OR deployment.release_id = 'salmonbus-d57370be9195520e'
        OR deployment.bundle_digest = 'd57370be9195520ecf3b0ef125aa3611090ed5f41ade2963c33f38d99a29e89a'
        OR deployment.calculation_version = 'seat-feature-contract-v4-1-2026-09-02'
    )
)
SELECT count(*) AS eligible_non_temporary_forecast_rows
FROM eligible_forecast;

WITH contaminated_generation AS (
    SELECT DISTINCT route_version_id, calculation_version, revision, data_until, computed_at
    FROM stop_demand_statistics
    WHERE computed_at >= TIMESTAMPTZ '2026-09-02T11:55:04.729493Z'
      AND computed_at < :'final_cutover_at'::timestamptz
      AND calculation_version IN (
          'seat-feature-contract-v4-1-2026-09-02',
          'observed-max-capacity-v1'
      )
)
SELECT count(*) AS eligible_non_temporary_statistics_rows
FROM stop_demand_statistics statistics
WHERE NOT EXISTS (
    SELECT 1
    FROM contaminated_generation contaminated
    WHERE contaminated.route_version_id = statistics.route_version_id
      AND contaminated.calculation_version = statistics.calculation_version
      AND contaminated.revision = statistics.revision
      AND contaminated.data_until = statistics.data_until
      AND contaminated.computed_at = statistics.computed_at
);

ROLLBACK;
