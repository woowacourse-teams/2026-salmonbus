SELECT jsonb_build_object('id', observation.id)::text
FROM vehicle_observation observation
JOIN vehicle_one_way_trip trip ON trip.id = observation.vehicle_trip_key
WHERE observation.id > coalesce((?::jsonb ->> 'id')::bigint, 0)
ORDER BY observation.id LIMIT ?
