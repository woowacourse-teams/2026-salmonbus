WITH bounds AS (SELECT ?::jsonb AS lower_key, ?::jsonb AS upper_key)
INSERT INTO forecast_evaluation(vehicle_observation_id, target_stop_order, route_version_id,
    scoring_state, arrival_observation_id, seats_on_arrival, scored_at, arrived_at, arrival_route_version_id,
    arrival_vehicle_id, arrival_stop_order, arrival_running_state, arrival_remaining_seats,
    arrival_seat_unknown_reason, arrival_vehicle_trip_key, arrival_quality_direction)
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.route_version_id,
       forecast.scoring_state, forecast.arrival_observation_id, forecast.seats_on_arrival, forecast.scored_at,
       batch.response_received_at, arrival.route_version_id, arrival.vehicle_id, arrival.stop_order,
       arrival.running_state, arrival.remaining_seats, arrival.seat_unknown_reason,
       arrival.vehicle_trip_key, arrival.quality_direction
FROM seat_forecast forecast
LEFT JOIN forecast_observation_quality arrival ON arrival.id = forecast.arrival_observation_id
LEFT JOIN observation_batch batch ON batch.id = arrival.observation_batch_id
CROSS JOIN bounds
WHERE (forecast.vehicle_observation_id, forecast.target_stop_order) >
    (coalesce((lower_key ->> 'id')::bigint, 0), coalesce((lower_key ->> 'stop')::integer, 0))
  AND (forecast.vehicle_observation_id, forecast.target_stop_order) <=
    ((upper_key ->> 'id')::bigint, (upper_key ->> 'stop')::integer)
ON CONFLICT DO NOTHING
