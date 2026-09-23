WITH cursor AS (SELECT ?::jsonb AS value)
SELECT jsonb_build_object('id', vehicle_observation_id, 'stop', target_stop_order)::text
FROM seat_forecast, cursor
WHERE (vehicle_observation_id, target_stop_order) >
    (coalesce((value ->> 'id')::bigint, 0), coalesce((value ->> 'stop')::integer, 0))
ORDER BY vehicle_observation_id, target_stop_order LIMIT ?
