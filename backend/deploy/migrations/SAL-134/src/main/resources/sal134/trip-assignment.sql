WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
INSERT INTO observation_trip_assignment(observation_id, trip_id)
SELECT observation.id, trip.id
FROM vehicle_observation observation JOIN vehicle_one_way_trip trip ON trip.id = observation.vehicle_trip_key, bounds
WHERE observation.id > coalesce((lower_key ->> 'id')::bigint, 0)
  AND observation.id <= (upper_key ->> 'id')::bigint
ON CONFLICT DO NOTHING
