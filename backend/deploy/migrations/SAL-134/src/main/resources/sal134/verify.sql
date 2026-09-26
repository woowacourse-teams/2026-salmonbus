-- 각 질의의 결과는 0이어야 한다. ID뿐 아니라 모든 이전 대상 값을 양방향 대조한다.
WITH expected AS (
SELECT batch.id AS source_batch_id, batch.attempt_number AS source_attempt_number, batch.route_version_id,
       min(forecast.model_deployment_id) AS model_deployment_id,
       min(forecast.demand_statistics_revision) AS demand_statistics_revision,
       min(forecast.quality_revision) AS quality_revision, batch.response_received_at AS observed_at,
       min(forecast.generated_at) AS generated_at, batch.forecast_completed_at AS published_at,
       count(forecast.vehicle_observation_id)::integer AS prediction_count,
       CASE WHEN count(forecast.vehicle_observation_id) = 0 THEN 'LEGACY_UNKNOWN' ELSE 'RECORDED' END AS provenance
FROM observation_batch batch
LEFT JOIN vehicle_observation observation ON observation.observation_batch_id = batch.id
LEFT JOIN seat_forecast forecast ON forecast.vehicle_observation_id = observation.id
WHERE batch.forecast_completed_at IS NOT NULL
GROUP BY batch.id
), missing AS (
    SELECT to_jsonb(expected) AS value FROM expected
    EXCEPT SELECT to_jsonb(target) - 'id' FROM forecast_publication target
), extra AS (
    SELECT to_jsonb(target) - 'id' AS value FROM forecast_publication target
    EXCEPT SELECT to_jsonb(expected) FROM expected
)
SELECT (SELECT count(*) FROM missing) + (SELECT count(*) FROM extra)
-- next-statement
SELECT count(*) FROM seat_forecast forecast
JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
LEFT JOIN forecast_publication publication ON publication.source_batch_id = observation.observation_batch_id
WHERE forecast.publication_id IS DISTINCT FROM publication.id OR publication.id IS NULL
-- next-statement
WITH quality_references AS (
    SELECT reference.observation_id, trip.assessed_at AS referenced_at
    FROM vehicle_one_way_trip trip
    CROSS JOIN LATERAL (VALUES (trip.start_observation_id), (trip.evidence_observation_id)) reference(observation_id)
    WHERE reference.observation_id IS NOT NULL
    UNION ALL
    SELECT reference.observation_id, investigation.investigated_at AS referenced_at
    FROM trip_quality_rebuild investigation
    CROSS JOIN LATERAL (VALUES (investigation.anchor_observation_id), (investigation.previous_observation_id),
        (investigation.boundary_candidate_observation_id), (investigation.evidence_observation_id)) reference(observation_id)
    WHERE reference.observation_id IS NOT NULL
),
quality_confirmations AS (
    SELECT observation.observation_batch_id, min(reference.referenced_at) AS confirmed_at
    FROM quality_references reference
    JOIN vehicle_observation observation ON observation.id = reference.observation_id
    GROUP BY observation.observation_batch_id
),
confirmations AS (
    SELECT batch.id, coalesce(batch.forecast_completed_at, min(forecast.scored_at), min(quality.confirmed_at)) AS confirmed_at
    FROM observation_batch batch
    LEFT JOIN vehicle_observation arrival ON arrival.observation_batch_id = batch.id
    LEFT JOIN seat_forecast forecast ON forecast.arrival_observation_id = arrival.id
    LEFT JOIN quality_confirmations quality ON quality.observation_batch_id = batch.id
    GROUP BY batch.id
)
SELECT count(*) FROM observation_batch batch JOIN confirmations ON confirmations.id = batch.id
WHERE batch.input_confirmed_at IS DISTINCT FROM confirmations.confirmed_at
-- next-statement
WITH expected AS (
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.route_version_id,
       forecast.scoring_state, forecast.arrival_observation_id, forecast.seats_on_arrival, forecast.scored_at,
       batch.response_received_at AS arrived_at, arrival.route_version_id AS arrival_route_version_id,
       arrival.vehicle_id AS arrival_vehicle_id, arrival.stop_order AS arrival_stop_order,
       arrival.running_state AS arrival_running_state, arrival.remaining_seats AS arrival_remaining_seats,
       arrival.seat_unknown_reason AS arrival_seat_unknown_reason, arrival.vehicle_trip_key AS arrival_vehicle_trip_key,
       arrival.quality_direction AS arrival_quality_direction
FROM seat_forecast forecast
LEFT JOIN forecast_observation_quality arrival ON arrival.id = forecast.arrival_observation_id
LEFT JOIN observation_batch batch ON batch.id = arrival.observation_batch_id
), missing AS (
    SELECT to_jsonb(expected) AS value FROM expected
    EXCEPT SELECT to_jsonb(target) FROM forecast_evaluation target
), extra AS (
    SELECT to_jsonb(target) AS value FROM forecast_evaluation target
    EXCEPT SELECT to_jsonb(expected) FROM expected
)
SELECT (SELECT count(*) FROM missing) + (SELECT count(*) FROM extra)
-- next-statement
WITH expected AS (
SELECT route_version_id, calculation_version, revision,
       min(data_until) AS data_until, min(computed_at) AS computed_at, min(quality_revision) AS quality_revision,
       count(*)::integer AS cell_count, NULL::bigint AS input_checkpoint
FROM stop_demand_statistics
GROUP BY route_version_id, calculation_version, revision
), missing AS (
    SELECT to_jsonb(expected) AS value FROM expected
    EXCEPT SELECT to_jsonb(target) FROM demand_statistics_version target
), extra AS (
    SELECT to_jsonb(target) AS value FROM demand_statistics_version target
    EXCEPT SELECT to_jsonb(expected) FROM expected
)
SELECT (SELECT count(*) FROM missing) + (SELECT count(*) FROM extra)
-- next-statement
WITH expected AS (
SELECT id AS route_id, quality_revision FROM route
), missing AS (
    SELECT to_jsonb(expected) AS value FROM expected
    EXCEPT SELECT to_jsonb(target) FROM route_data_quality target
), extra AS (
    SELECT to_jsonb(target) AS value FROM route_data_quality target
    EXCEPT SELECT to_jsonb(expected) FROM expected
)
SELECT (SELECT count(*) FROM missing) + (SELECT count(*) FROM extra)
-- next-statement
WITH expected AS (
SELECT id AS route_version_id, maximum_observation_gap_seconds, observation_gap_evidence FROM route_version
), missing AS (
    SELECT to_jsonb(expected) AS value FROM expected
    EXCEPT SELECT to_jsonb(target) FROM route_version_quality_policy target
), extra AS (
    SELECT to_jsonb(target) AS value FROM route_version_quality_policy target
    EXCEPT SELECT to_jsonb(expected) FROM expected
)
SELECT (SELECT count(*) FROM missing) + (SELECT count(*) FROM extra)
-- next-statement
WITH expected AS (
SELECT observation.id AS observation_id, trip.id AS trip_id
FROM vehicle_observation observation JOIN vehicle_one_way_trip trip ON trip.id = observation.vehicle_trip_key
), missing AS (
    SELECT to_jsonb(expected) AS value FROM expected
    EXCEPT SELECT to_jsonb(target) FROM observation_trip_assignment target
), extra AS (
    SELECT to_jsonb(target) AS value FROM observation_trip_assignment target
    EXCEPT SELECT to_jsonb(expected) FROM expected
)
SELECT (SELECT count(*) FROM missing) + (SELECT count(*) FROM extra)
-- next-statement
WITH expected AS (
SELECT 1::smallint AS id, (SELECT id FROM model_deployment WHERE state = 'ACTIVE') AS model_deployment_id,
       CASE WHEN EXISTS (SELECT 1 FROM model_deployment WHERE state = 'ACTIVE') THEN 1 ELSE 0 END::bigint AS version
), missing AS (
    SELECT to_jsonb(expected) AS value FROM expected
    EXCEPT SELECT to_jsonb(target) FROM model_active_slot target
), extra AS (
    SELECT to_jsonb(target) AS value FROM model_active_slot target
    EXCEPT SELECT to_jsonb(expected) FROM expected
)
SELECT (SELECT count(*) FROM missing) + (SELECT count(*) FROM extra)
-- next-statement
SELECT count(*) FROM model_activation_request
