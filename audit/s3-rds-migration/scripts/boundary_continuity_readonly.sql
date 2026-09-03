\set ON_ERROR_STOP on

-- Aggregate-only post-import acceptance. Requires ingestion_origin. It never
-- selects, prints, hashes, or persists an individual vehicle_id value.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

WITH boundaries AS (
    SELECT *
    FROM (VALUES
        (
            '3330'::text,
            '204000057'::varchar,
            1::bigint,
            timestamptz '2026-09-02T10:27:51.330754Z',
            timestamptz '2026-09-02T10:27:45.315Z',
            timestamptz '2026-09-02T10:27:52.390820Z',
            interval '30 minutes'
        ),
        (
            '1650'::text,
            '234000050'::varchar,
            2::bigint,
            timestamptz '2026-09-02T12:49:33.041299Z',
            timestamptz '2026-09-02T12:49:31.467Z',
            NULL::timestamptz,
            interval '30 minutes'
        )
    ) AS boundary(
        route_name,
        public_route_id,
        expected_route_version_id,
        target_authority_from,
        expected_source_last_response,
        known_target_first_response,
        boundary_window
    )
), route_batches AS (
    SELECT boundary.route_name,
           boundary.expected_route_version_id,
           batch.id,
           batch.route_version_id,
           batch.ingestion_origin,
           batch.response_received_at
    FROM boundaries boundary
    JOIN route ON route.public_route_id = boundary.public_route_id
    JOIN route_version version ON version.route_id = route.id
    JOIN observation_batch batch ON batch.route_version_id = version.id
    WHERE batch.response_received_at >= boundary.target_authority_from - boundary.boundary_window
      AND batch.response_received_at < boundary.target_authority_from + boundary.boundary_window
), source_batches AS (
    SELECT *
    FROM route_batches
    WHERE ingestion_origin = 'S3_BACKFILL'
      AND response_received_at < (
          SELECT target_authority_from
          FROM boundaries
          WHERE boundaries.route_name = route_batches.route_name
      )
), live_batches AS (
    SELECT *
    FROM route_batches
    WHERE ingestion_origin = 'LIVE'
      AND response_received_at >= (
          SELECT target_authority_from
          FROM boundaries
          WHERE boundaries.route_name = route_batches.route_name
      )
), source_vehicles AS (
    SELECT DISTINCT batch.route_name,
           batch.route_version_id,
           observation.vehicle_id
    FROM source_batches batch
    JOIN vehicle_observation observation
      ON observation.observation_batch_id = batch.id
     AND observation.route_version_id = batch.route_version_id
    WHERE observation.vehicle_id IS NOT NULL
), live_vehicles AS (
    SELECT DISTINCT batch.route_name,
           batch.route_version_id,
           observation.vehicle_id
    FROM live_batches batch
    JOIN vehicle_observation observation
      ON observation.observation_batch_id = batch.id
     AND observation.route_version_id = batch.route_version_id
    WHERE observation.vehicle_id IS NOT NULL
), source_vehicle_ids AS (
    SELECT DISTINCT route_name, vehicle_id
    FROM source_vehicles
), live_vehicle_ids AS (
    SELECT DISTINCT route_name, vehicle_id
    FROM live_vehicles
), shared_private_identity AS (
    SELECT source.route_name, COUNT(*) AS shared_vehicle_ids
    FROM source_vehicle_ids source
    JOIN live_vehicle_ids live
      ON live.route_name = source.route_name
     AND live.vehicle_id = source.vehicle_id
    GROUP BY source.route_name
), shared_exact_version AS (
    SELECT source.route_name,
           COUNT(DISTINCT source.vehicle_id) AS shared_vehicle_route_versions
    FROM source_vehicles source
    JOIN live_vehicles live
      ON live.route_name = source.route_name
     AND live.vehicle_id = source.vehicle_id
     AND live.route_version_id = source.route_version_id
    JOIN boundaries boundary
      ON boundary.route_name = source.route_name
     AND source.route_version_id = boundary.expected_route_version_id
    GROUP BY source.route_name
), time_edges AS (
    SELECT boundary.*,
           (SELECT MAX(response_received_at)
            FROM source_batches
            WHERE source_batches.route_name = boundary.route_name) AS source_last_response,
           (SELECT MIN(response_received_at)
            FROM live_batches
            WHERE live_batches.route_name = boundary.route_name) AS target_first_response
    FROM boundaries boundary
), route_metrics AS (
    SELECT edge.*,
           (SELECT COUNT(*)
            FROM source_batches
            WHERE source_batches.route_name = edge.route_name) AS source_batches,
           (SELECT COUNT(*)
            FROM live_batches
            WHERE live_batches.route_name = edge.route_name) AS live_batches,
           (SELECT COUNT(*)
            FROM vehicle_observation observation
            JOIN source_batches batch ON batch.id = observation.observation_batch_id
            WHERE batch.route_name = edge.route_name) AS source_observations,
           (SELECT COUNT(*)
            FROM vehicle_observation observation
            JOIN live_batches batch ON batch.id = observation.observation_batch_id
            WHERE batch.route_name = edge.route_name) AS live_observations,
           (SELECT COUNT(*)
            FROM source_batches
            WHERE source_batches.route_name = edge.route_name
              AND source_batches.route_version_id <> edge.expected_route_version_id)
               AS source_unexpected_version_batches,
           (SELECT COUNT(*)
            FROM live_batches
            WHERE live_batches.route_name = edge.route_name
              AND live_batches.route_version_id <> edge.expected_route_version_id)
               AS live_unexpected_version_batches,
           COALESCE(shared_private_identity.shared_vehicle_ids, 0) AS shared_vehicle_ids,
           COALESCE(shared_exact_version.shared_vehicle_route_versions, 0)
               AS shared_vehicle_route_versions
    FROM time_edges edge
    LEFT JOIN shared_private_identity USING (route_name)
    LEFT JOIN shared_exact_version USING (route_name)
), route_results AS (
    SELECT route_name,
           jsonb_build_object(
               'public_route_id', public_route_id,
               'expected_route_version_id', expected_route_version_id,
               'target_authority_from', target_authority_from,
               'window_seconds_each_side', extract(epoch FROM boundary_window),
               'expected_source_last_response', expected_source_last_response,
               'source_last_response', source_last_response,
               'source_last_matches_frozen_receipt',
                   COALESCE(source_last_response = expected_source_last_response, false),
               'known_target_first_response', known_target_first_response,
               'target_first_response', target_first_response,
               'known_target_first_matches',
                   CASE
                       WHEN known_target_first_response IS NULL THEN NULL
                       ELSE COALESCE(target_first_response = known_target_first_response, false)
                   END,
               'observed_gap_seconds',
                   extract(epoch FROM target_first_response - source_last_response),
               'row_counts', jsonb_build_object(
                   'source_batches', source_batches,
                   'live_batches', live_batches,
                   'source_observations', source_observations,
                   'live_observations', live_observations
               ),
               'unexpected_route_version_batches', jsonb_build_object(
                   'source', source_unexpected_version_batches,
                   'live', live_unexpected_version_batches
               ),
               'shared_vehicle_ids', shared_vehicle_ids,
               'shared_vehicle_exact_route_version', shared_vehicle_route_versions
           ) AS result
    FROM route_metrics
), routes AS (
    SELECT jsonb_object_agg(route_name, result ORDER BY route_name) AS results
    FROM route_results
)
SELECT jsonb_pretty(
    jsonb_build_object(
        'schema_version', 'salmonbus-boundary-continuity-readonly-v2',
        'authority_policy', 'RDS_ROUTE_VERSION_VALID_FROM_CONSERVATIVE_NO_LIVE_ROW_OVERLAP',
        'read_only', true,
        'routes', routes.results,
        'vehicle_values_emitted', false,
        'mutation_performed', false
    )
)
FROM routes;

ROLLBACK;
