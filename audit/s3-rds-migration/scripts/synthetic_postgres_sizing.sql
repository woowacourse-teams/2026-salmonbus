\set ON_ERROR_STOP on

-- Synthetic sizing only. Values are generated and have no relationship to a
-- source row or private identifier. Run against a disposable database after
-- applying backend Flyway V1..V12.

SELECT clock_timestamp() AS batch_started \gset

INSERT INTO route (
    public_route_id, source_id, source_route_id, display_name,
    start_stop_name, end_stop_name
) VALUES
    ('900000001', 'SYNTHETIC', '900000001', 'TEST-A', 'START', 'END'),
    ('900000002', 'SYNTHETIC', '900000002', 'TEST-B', 'START', 'END');

INSERT INTO route_version (
    route_id, turn_sequence, content_digest, valid_from
)
SELECT id,
       CASE WHEN id % 2 = 0 THEN 43 ELSE 44 END,
       lpad(to_hex(id), 64, '0'),
       timestamptz '2000-01-01 00:00:00+00'
FROM route;

INSERT INTO route_stop (
    route_version_id, stop_order, stop_id, name, direction, boarding_allowed
)
SELECT version.id,
       sequence,
       lpad((version.id * 1000 + sequence)::text, 9, '0'),
       'SYNTHETIC-' || sequence,
       CASE WHEN sequence <= version.turn_sequence THEN 'UP' ELSE 'DOWN' END,
       true
FROM route_version version
CROSS JOIN LATERAL generate_series(
    1,
    CASE WHEN version.turn_sequence = 44 THEN 89 ELSE 85 END
) sequence;

INSERT INTO observation_batch (
    route_version_id,
    scheduled_at,
    attempt_number,
    attempt_key,
    requested_at,
    response_received_at,
    http_status,
    result_code,
    outcome,
    provider_rows,
    stored_rows,
    excluded_rows,
    normalization_version,
    collection_strategy_version
)
SELECT 1 + (series % 2),
       timestamptz '2000-01-01 00:00:00+00' + series * interval '10 seconds',
       1,
       's3v1:' || lpad(to_hex(series), 64, '0'),
       timestamptz '2000-01-01 00:00:00+00' + series * interval '10 seconds',
       timestamptz '2000-01-01 00:00:00.1+00' + series * interval '10 seconds',
       200,
       0,
       'SUCCESS_ROWS',
       17,
       17,
       0,
       'normalization-v1.0.0-s3-backfill',
       'adaptive-kst-v1.2.0'
FROM generate_series(1, :batch_count::bigint) series;

SELECT extract(epoch FROM clock_timestamp() - :'batch_started'::timestamptz) AS batch_seconds \gset
SELECT clock_timestamp() AS observation_started \gset

WITH generated AS (
    SELECT series,
           1 + ((series - 1) % :batch_count::bigint) AS batch_id,
           ((series - 1) / :batch_count::bigint)::integer AS source_row_number
    FROM generate_series(1, :observation_count::bigint) series
), contextual AS (
    SELECT generated.*,
           batch.route_version_id,
           CASE
               WHEN batch.route_version_id = 1
                   THEN 1 + (generated.source_row_number % 89)
               ELSE 1 + (generated.source_row_number % 85)
           END AS stop_order,
           generated.source_row_number % 3 AS running_state
    FROM generated
    JOIN observation_batch batch ON batch.id = generated.batch_id
)
INSERT INTO vehicle_observation (
    observation_batch_id,
    route_version_id,
    source_row_number,
    vehicle_id,
    vehicle_trip_key,
    plate_number,
    stop_order,
    stop_id,
    running_state,
    remaining_seats,
    crowd_level,
    vehicle_type,
    route_type,
    tagless,
    seat_unknown_reason,
    passed_stop_order
)
SELECT batch_id,
       route_version_id,
       source_row_number,
       lpad(source_row_number::text, 9, '0'),
       null,
       null,
       stop_order,
       lpad((route_version_id * 1000 + stop_order)::text, 9, '0'),
       running_state,
       CASE WHEN series % 1000 = 0 THEN null ELSE (series % 69)::integer END,
       CASE WHEN series % 5 = 0 THEN null ELSE 1 + (series % 4)::integer END,
       (series % 3)::integer,
       1,
       CASE WHEN series % 7 = 0 THEN null ELSE 0 END,
       CASE WHEN series % 1000 = 0 THEN 'REPORTED_UNKNOWN' ELSE null END,
       CASE WHEN running_state = 1 THEN stop_order - 1 ELSE stop_order END
FROM contextual;

SELECT extract(epoch FROM clock_timestamp() - :'observation_started'::timestamptz) AS observation_seconds \gset

ANALYZE observation_batch;
ANALYZE vehicle_observation;

WITH relation_sizes AS (
    SELECT relname,
           pg_relation_size(relid) AS heap_bytes,
           pg_indexes_size(relid) AS index_bytes,
           pg_total_relation_size(relid) AS total_bytes
    FROM pg_catalog.pg_statio_user_tables
    WHERE relname IN ('observation_batch', 'vehicle_observation')
), index_sizes AS (
    SELECT tablename,
           jsonb_object_agg(indexname, pg_relation_size(indexname::regclass) ORDER BY indexname) AS bytes
    FROM pg_indexes
    WHERE schemaname = 'public'
      AND tablename IN ('observation_batch', 'vehicle_observation')
    GROUP BY tablename
), average_rows AS (
    SELECT 'observation_batch' AS relname,
           round(avg(pg_column_size(sampled)))::bigint AS average_row_bytes
    FROM (SELECT * FROM observation_batch LIMIT 10000) sampled
    UNION ALL
    SELECT 'vehicle_observation' AS relname,
           round(avg(pg_column_size(sampled)))::bigint AS average_row_bytes
    FROM (SELECT * FROM vehicle_observation LIMIT 10000) sampled
)
SELECT jsonb_pretty(
    jsonb_build_object(
        'schema_version', 'salmonbus-synthetic-postgres-sizing-v1',
        'postgres_version', current_setting('server_version'),
        'synthetic_only', true,
        'input_rows', jsonb_build_object(
            'observation_batch', :batch_count::bigint,
            'vehicle_observation', :observation_count::bigint
        ),
        'insert_seconds_local_lower_bound', jsonb_build_object(
            'observation_batch', :'batch_seconds'::numeric,
            'vehicle_observation', :'observation_seconds'::numeric
        ),
        'relations', (
            SELECT jsonb_object_agg(
                sizes.relname,
                jsonb_build_object(
                    'heap_bytes', sizes.heap_bytes,
                    'index_bytes', sizes.index_bytes,
                    'total_bytes', sizes.total_bytes,
                    'average_row_bytes', average_rows.average_row_bytes,
                    'indexes', index_sizes.bytes
                )
            )
            FROM relation_sizes sizes
            JOIN index_sizes ON index_sizes.tablename = sizes.relname
            JOIN average_rows ON average_rows.relname = sizes.relname
        ),
        'database_bytes', pg_database_size(current_database())
    )
);
