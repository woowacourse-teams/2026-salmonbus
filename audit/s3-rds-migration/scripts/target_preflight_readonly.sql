\set ON_ERROR_STOP on

-- Aggregate-only target preflight. It exposes no vehicle or plate value and
-- performs no mutation. Run only from an already authorized academy session.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

WITH target_counts AS (
    SELECT
        (SELECT COUNT(*) FROM observation_batch) AS batch_rows,
        (SELECT COUNT(*) FROM vehicle_observation) AS observation_rows,
        (SELECT COUNT(*) FROM seat_forecast) AS forecast_rows,
        (SELECT COUNT(*) FROM stop_demand_statistics) AS statistics_rows,
        (SELECT COUNT(*) FROM model_deployment) AS deployment_rows,
        (SELECT MIN(response_received_at) FROM observation_batch) AS first_response_received_at,
        (SELECT MAX(response_received_at) FROM observation_batch) AS last_response_received_at
), private_columns AS (
    SELECT COUNT(*) FILTER (WHERE vehicle_id IS NULL) AS vehicle_id_null_rows,
           COUNT(*) FILTER (WHERE vehicle_id IS NOT NULL) AS vehicle_id_present_rows,
           COUNT(DISTINCT vehicle_id) AS distinct_vehicle_ids,
           COUNT(*) FILTER (WHERE plate_number IS NULL) AS plate_number_null_rows,
           COUNT(*) FILTER (WHERE plate_number IS NOT NULL) AS plate_number_present_rows,
           COUNT(DISTINCT plate_number) AS distinct_plate_numbers
    FROM vehicle_observation
), duplicate_vehicle_groups AS (
    SELECT COUNT(*) AS groups,
           COALESCE(SUM(row_count - 1), 0) AS rows_beyond_first
    FROM (
        SELECT observation_batch_id, vehicle_id, COUNT(*) AS row_count
        FROM vehicle_observation
        WHERE vehicle_id IS NOT NULL
        GROUP BY observation_batch_id, vehicle_id
        HAVING COUNT(*) > 1
    ) duplicates
), import_namespace AS (
    SELECT COUNT(*) FILTER (WHERE attempt_key LIKE 's3v1:%') AS preexisting_s3v1_attempt_keys
    FROM observation_batch
), required_columns AS (
    SELECT COUNT(*) FILTER (
               WHERE table_name = 'observation_batch' AND column_name = 'ingestion_origin'
           ) = 1 AS has_ingestion_origin,
           COUNT(*) FILTER (
               WHERE table_name = 'vehicle_observation' AND column_name = 'vehicle_id'
           ) = 1 AS has_vehicle_id
    FROM information_schema.columns
    WHERE table_schema = 'public'
), route_state AS (
    SELECT (SELECT COUNT(*) FROM route) AS route_rows,
           (SELECT COUNT(*) FROM route_version) AS route_version_rows,
           (SELECT COUNT(*) FROM route_version WHERE valid_to IS NULL) AS active_route_version_rows,
           (SELECT COUNT(*) FROM route_stop) AS route_stop_rows,
           (SELECT COUNT(*)
            FROM route
            WHERE public_route_id IN ('204000057', '234000050')) AS source_route_id_matches,
           (SELECT COUNT(*)
            FROM route
            JOIN route_version ON route_version.route_id = route.id
            WHERE route.public_route_id = '204000057'
              AND route_version.id = 1
              AND route_version.valid_from = timestamptz '2026-09-02T10:27:51.330754Z'
              AND route_version.valid_to IS NULL
              AND route_version.turn_sequence = 43
              AND route_version.content_digest =
                  '91749006e76e5f822c1c2e241b37fae4eba6941e217dcba2928c6e7e8ffdae5d')
               AS exact_current_3330_version_rows,
           (SELECT COUNT(*)
            FROM route
            JOIN route_version ON route_version.route_id = route.id
            WHERE route.public_route_id = '234000050'
              AND route.id = 2
              AND route_version.id = 2
              AND route_version.valid_from = timestamptz '2026-09-02T12:49:33.041299Z'
              AND route_version.valid_to IS NULL
              AND route_version.turn_sequence = 44
              AND route_version.content_digest =
                  'f6b5a3b0fabd731dbbab36054826be26714061c42d66b862eac60b191b4b51bc')
               AS exact_current_1650_version_rows,
           (SELECT COUNT(*)
            FROM route JOIN route_version ON route_version.route_id = route.id
            WHERE route.public_route_id = '204000057') AS route_3330_version_rows,
           (SELECT COUNT(*)
            FROM route JOIN route_version ON route_version.route_id = route.id
            WHERE route.public_route_id = '234000050') AS route_1650_version_rows,
           (SELECT COUNT(*) FROM route_stop WHERE route_version_id = 1) AS route_3330_stop_rows,
           (SELECT COUNT(DISTINCT stop_id) FROM route_stop WHERE route_version_id = 1)
               AS route_3330_unique_stop_ids,
           (SELECT COUNT(*) FILTER (WHERE NOT boarding_allowed)
            FROM route_stop WHERE route_version_id = 1) AS route_3330_nonboarding_stops,
           (SELECT COUNT(*) FROM route_stop WHERE route_version_id = 2) AS route_1650_stop_rows,
           (SELECT COUNT(DISTINCT stop_id) FROM route_stop WHERE route_version_id = 2)
               AS route_1650_unique_stop_ids,
           (SELECT COUNT(*) FILTER (WHERE NOT boarding_allowed)
            FROM route_stop WHERE route_version_id = 2) AS route_1650_nonboarding_stops
), observation_rows_by_version AS (
    SELECT route_version_id, COUNT(*) AS observation_rows
    FROM vehicle_observation
    GROUP BY route_version_id
), live_route_range_rows AS (
    SELECT route.public_route_id,
           route_version.id AS route_version_id,
           COUNT(batch.id) AS batch_rows,
           COALESCE(MAX(observed.observation_rows), 0) AS observation_rows,
           MIN(batch.response_received_at) AS first_response_received_at,
           MAX(batch.response_received_at) AS last_response_received_at,
           MAX(batch.response_received_at)
               FILTER (WHERE batch.outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY'))
               AS last_success_response_received_at,
           MIN(batch.response_received_at)
               FILTER (WHERE batch.failure_code = 'DAILY_QUOTA_EXCEEDED')
               AS first_daily_quota_failure_at,
           COUNT(*) FILTER (WHERE batch.failure_code = 'DAILY_QUOTA_EXCEEDED')
               AS daily_quota_failure_batches
    FROM route
    JOIN route_version ON route_version.route_id = route.id
    LEFT JOIN observation_batch batch ON batch.route_version_id = route_version.id
    LEFT JOIN observation_rows_by_version observed ON observed.route_version_id = route_version.id
    WHERE route.public_route_id IN ('204000057', '234000050')
    GROUP BY route.public_route_id, route_version.id
), live_route_ranges AS (
    SELECT COALESCE(
               jsonb_object_agg(
                   public_route_id,
                   jsonb_build_object(
                       'route_version_id', route_version_id,
                       'batch_rows', batch_rows,
                       'observation_rows', observation_rows,
                       'first_response_received_at', first_response_received_at,
                       'last_response_received_at', last_response_received_at,
                       'last_success_response_received_at', last_success_response_received_at,
                       'first_daily_quota_failure_at', first_daily_quota_failure_at,
                       'daily_quota_failure_batches', daily_quota_failure_batches
                   )
                   ORDER BY public_route_id
               ),
               '{}'::jsonb
           ) AS ranges
    FROM live_route_range_rows
), relation_sizes AS (
    SELECT COALESCE(
               jsonb_object_agg(
                   relname,
                   jsonb_build_object(
                       'heap_bytes', pg_relation_size(relid),
                       'index_bytes', pg_indexes_size(relid),
                       'total_bytes', pg_total_relation_size(relid)
                   )
                   ORDER BY relname
               ),
               '{}'::jsonb
           ) AS sizes
    FROM pg_catalog.pg_statio_user_tables
    WHERE relname IN ('observation_batch', 'vehicle_observation', 'seat_forecast', 'stop_demand_statistics')
), temporary_identity AS (
    SELECT COUNT(*) AS exact_rows,
           COUNT(*) FILTER (WHERE state = 'RETIRED') AS retired_rows,
           (SELECT COUNT(*) FROM seat_forecast WHERE model_deployment_id = 1)
               AS temporary_forecast_rows
    FROM model_deployment
    WHERE id = 1
      AND release_id = 'salmonbus-d57370be9195520e'
      AND bundle_digest = 'd57370be9195520ecf3b0ef125aa3611090ed5f41ade2963c33f38d99a29e89a'
      AND calculation_version = 'seat-feature-contract-v4-1-2026-09-02'
      AND activated_at = timestamptz '2026-09-02T11:55:04.729493Z'
)
SELECT jsonb_pretty(
    jsonb_build_object(
        'schema_version', 'salmonbus-target-preflight-readonly-v1',
        'transaction_read_only', current_setting('transaction_read_only'),
        'snapshot', pg_current_snapshot()::text,
        'counts', to_jsonb(target_counts),
        'private_column_aggregates', to_jsonb(private_columns),
        'duplicate_vehicle_groups', to_jsonb(duplicate_vehicle_groups),
        'preexisting_import_namespace', to_jsonb(import_namespace),
        'required_columns', to_jsonb(required_columns),
        'route_state', to_jsonb(route_state),
        'live_route_ranges', live_route_ranges.ranges,
        'relation_sizes', relation_sizes.sizes,
        'temporary_identity', to_jsonb(temporary_identity),
        'mutation_performed', false
    )
)
FROM target_counts,
     private_columns,
     duplicate_vehicle_groups,
     import_namespace,
     required_columns,
     route_state,
     live_route_ranges,
     relation_sizes,
     temporary_identity;

ROLLBACK;
