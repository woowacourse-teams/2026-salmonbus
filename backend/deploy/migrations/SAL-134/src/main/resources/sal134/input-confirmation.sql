WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key),
quality_references AS (
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
    CROSS JOIN bounds
    WHERE observation.observation_batch_id > coalesce((lower_key ->> 'id')::bigint, 0)
      AND observation.observation_batch_id <= (upper_key ->> 'id')::bigint
    GROUP BY observation.observation_batch_id
),
confirmations AS (
    SELECT batch.id, coalesce(batch.forecast_completed_at, min(forecast.scored_at), min(quality.confirmed_at)) AS confirmed_at
    FROM observation_batch batch
    LEFT JOIN vehicle_observation arrival ON arrival.observation_batch_id = batch.id
    LEFT JOIN seat_forecast forecast ON forecast.arrival_observation_id = arrival.id
    LEFT JOIN quality_confirmations quality ON quality.observation_batch_id = batch.id
    CROSS JOIN bounds
    WHERE batch.id > coalesce((lower_key ->> 'id')::bigint, 0)
      AND batch.id <= (upper_key ->> 'id')::bigint
    GROUP BY batch.id
)
UPDATE observation_batch batch SET input_confirmed_at = confirmations.confirmed_at
FROM confirmations
WHERE batch.id = confirmations.id AND batch.input_confirmed_at IS NULL
  AND confirmations.confirmed_at IS NOT NULL
