\set ON_ERROR_STOP on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '3s';
SET LOCAL max_parallel_workers_per_gather = 0;
SET LOCAL work_mem = '8MB';
SET LOCAL TIME ZONE 'Asia/Seoul';

WITH route_context AS (
    SELECT v.id, v.turn_sequence, min(s.stop_order) AS first_stop
    FROM route_version v JOIN route_stop s ON s.route_version_id = v.id
    WHERE v.id = :'route_version_id'::bigint
    GROUP BY v.id, v.turn_sequence
), observations AS (
    SELECT o.id, o.vehicle_id, o.stop_order, o.running_state,
           b.id AS batch_id, b.response_received_at AS at,
           r.turn_sequence, r.first_stop
    FROM observation_batch b
    JOIN vehicle_observation o ON o.observation_batch_id = b.id AND o.route_version_id = b.route_version_id
    JOIN route_context r ON r.id = b.route_version_id
    WHERE b.route_version_id = :'route_version_id'::bigint
      AND b.response_received_at >= :'from_at'::timestamptz
      AND b.response_received_at < :'until_at'::timestamptz
      AND o.vehicle_id IS NOT NULL
), ordered AS (
    SELECT *, lag(stop_order) OVER w AS previous_stop, lag(at) OVER w AS previous_at
    FROM observations
    WINDOW w AS (PARTITION BY vehicle_id ORDER BY at, batch_id, id)
)
SELECT date_trunc('hour', at) AS hour_kst,
       count(*) AS observation_pairs,
       round(avg(extract(epoch FROM at - previous_at))::numeric, 2) AS avg_seconds,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM at - previous_at)) AS p50_seconds,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM at - previous_at)) AS p95_seconds,
       percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM at - previous_at)) AS p99_seconds,
       max(extract(epoch FROM at - previous_at)) AS max_seconds,
       count(*) FILTER (WHERE at = previous_at) AS same_timestamp_pairs
FROM ordered WHERE previous_at IS NOT NULL
GROUP BY 1 ORDER BY 1;

WITH route_context AS (
    SELECT v.id, v.turn_sequence, min(s.stop_order) AS first_stop
    FROM route_version v JOIN route_stop s ON s.route_version_id = v.id
    WHERE v.id = :'route_version_id'::bigint
    GROUP BY v.id, v.turn_sequence
), observations AS (
    SELECT o.id, o.vehicle_id, o.stop_order, o.running_state,
           b.id AS batch_id, b.response_received_at AS at,
           r.turn_sequence, r.first_stop
    FROM observation_batch b
    JOIN vehicle_observation o ON o.observation_batch_id = b.id AND o.route_version_id = b.route_version_id
    JOIN route_context r ON r.id = b.route_version_id
    WHERE b.route_version_id = :'route_version_id'::bigint
      AND b.response_received_at >= :'from_at'::timestamptz
      AND b.response_received_at < :'until_at'::timestamptz
      AND o.vehicle_id IS NOT NULL
), ordered AS (
    SELECT *, lag(stop_order) OVER w AS previous_stop, lag(at) OVER w AS previous_at
    FROM observations
    WINDOW w AS (PARTITION BY vehicle_id ORDER BY at, batch_id, id)
), entries AS (
    SELECT *, lag(at) OVER w AS previous_entry_at,
           lag(stop_order) OVER w AS previous_entry_stop,
           lag(previous_at IS NULL) OVER w AS previous_entry_truncated
    FROM ordered WHERE previous_stop IS DISTINCT FROM stop_order
    WINDOW w AS (PARTITION BY vehicle_id ORDER BY at, batch_id, id)
), durations AS (
    SELECT CASE WHEN turn_sequence IS NULL THEN 'UNKNOWN'
                WHEN stop_order <= turn_sequence THEN 'UP' ELSE 'DOWN' END AS direction,
           extract(epoch FROM at - previous_entry_at) / 60.0 AS minutes
    FROM entries
    WHERE stop_order = previous_entry_stop + 1
      AND NOT previous_entry_truncated
)
SELECT direction, count(*) AS samples, round(avg(minutes), 3) AS avg_minutes,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY minutes) AS p50_minutes,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY minutes) AS p95_minutes,
       min(minutes) AS min_minutes, max(minutes) AS max_minutes
FROM durations GROUP BY direction ORDER BY direction;

WITH route_context AS (
    SELECT v.id, v.turn_sequence, min(s.stop_order) AS first_stop
    FROM route_version v JOIN route_stop s ON s.route_version_id = v.id
    WHERE v.id = :'route_version_id'::bigint
    GROUP BY v.id, v.turn_sequence
), observations AS (
    SELECT o.id, o.vehicle_id, o.stop_order, o.running_state,
           b.id AS batch_id, b.response_received_at AS at,
           r.turn_sequence, r.first_stop
    FROM observation_batch b
    JOIN vehicle_observation o ON o.observation_batch_id = b.id AND o.route_version_id = b.route_version_id
    JOIN route_context r ON r.id = b.route_version_id
    WHERE b.route_version_id = :'route_version_id'::bigint
      AND b.response_received_at >= :'from_at'::timestamptz
      AND b.response_received_at < :'until_at'::timestamptz
      AND o.vehicle_id IS NOT NULL
), ordered AS (
    SELECT *, lag(stop_order) OVER w AS previous_stop, lag(at) OVER w AS previous_at
    FROM observations
    WINDOW w AS (PARTITION BY vehicle_id ORDER BY at, batch_id, id)
), visits AS (
    SELECT *, sum(CASE WHEN previous_stop IS DISTINCT FROM stop_order THEN 1 ELSE 0 END)
        OVER (PARTITION BY vehicle_id ORDER BY at, batch_id, id ROWS UNBOUNDED PRECEDING) AS visit_id
    FROM ordered
), departures AS (
    SELECT vehicle_id, visit_id, stop_order,
           min(at) FILTER (WHERE running_state = 2) AS departed_at
    FROM visits
    WHERE stop_order IN (first_stop, turn_sequence)
    GROUP BY vehicle_id, visit_id, stop_order
    HAVING count(*) FILTER (WHERE running_state = 2) > 0
       AND NOT bool_or(previous_at IS NULL)
), compared AS (
    SELECT *, lag(stop_order) OVER w AS previous_departure_stop,
           lag(departed_at) OVER w AS previous_departure_at,
           lag(stop_order, 2) OVER w AS two_departures_ago_stop,
           lag(departed_at, 2) OVER w AS two_departures_ago_at
    FROM departures
    WINDOW w AS (PARTITION BY vehicle_id ORDER BY departed_at, visit_id)
), intervals AS (
    SELECT vehicle_id, 'ONE_WAY_CANDIDATE' AS kind,
           previous_departure_stop AS from_stop, stop_order AS to_stop,
           previous_departure_at AS from_at, departed_at AS until_at
    FROM compared
    WHERE previous_departure_at IS NOT NULL AND stop_order <> previous_departure_stop
    UNION ALL
    SELECT vehicle_id, 'ROUND_TRIP_CANDIDATE', two_departures_ago_stop, stop_order,
           two_departures_ago_at, departed_at
    FROM compared
    WHERE two_departures_ago_stop = stop_order AND previous_departure_stop <> stop_order
    UNION ALL
    SELECT vehicle_id, 'SAME_TERMINAL_REAPPEARANCE', previous_departure_stop, stop_order,
           previous_departure_at, departed_at
    FROM compared
    WHERE previous_departure_stop = stop_order
)
SELECT kind, from_stop, to_stop, count(*) AS samples,
       min(extract(epoch FROM until_at - from_at) / 60.0) AS min_minutes,
       percentile_cont(0.1) WITHIN GROUP (ORDER BY extract(epoch FROM until_at - from_at) / 60.0) AS p10_minutes,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM until_at - from_at) / 60.0) AS p50_minutes,
       max(extract(epoch FROM until_at - from_at) / 60.0) AS max_minutes
FROM intervals GROUP BY kind, from_stop, to_stop ORDER BY kind, from_stop, to_stop;

WITH route_context AS (
    SELECT v.id, v.turn_sequence, min(s.stop_order) AS first_stop
    FROM route_version v JOIN route_stop s ON s.route_version_id = v.id
    WHERE v.id = :'route_version_id'::bigint
    GROUP BY v.id, v.turn_sequence
), observations AS (
    SELECT o.id, o.vehicle_id, o.stop_order, o.running_state,
           b.id AS batch_id, b.response_received_at AS at,
           r.turn_sequence, r.first_stop
    FROM observation_batch b
    JOIN vehicle_observation o ON o.observation_batch_id = b.id AND o.route_version_id = b.route_version_id
    JOIN route_context r ON r.id = b.route_version_id
    WHERE b.route_version_id = :'route_version_id'::bigint
      AND b.response_received_at >= :'from_at'::timestamptz
      AND b.response_received_at < :'until_at'::timestamptz
      AND o.vehicle_id IS NOT NULL
), ordered AS (
    SELECT *, lag(stop_order) OVER w AS previous_stop, lag(at) OVER w AS previous_at
    FROM observations
    WINDOW w AS (PARTITION BY vehicle_id ORDER BY at, batch_id, id)
), visits AS (
    SELECT *, sum(CASE WHEN previous_stop IS DISTINCT FROM stop_order THEN 1 ELSE 0 END)
        OVER (PARTITION BY vehicle_id ORDER BY at, batch_id, id ROWS UNBOUNDED PRECEDING) AS visit_id
    FROM ordered
), departures AS (
    SELECT vehicle_id, visit_id, stop_order,
           min(at) FILTER (WHERE running_state = 2) AS departed_at
    FROM visits
    WHERE stop_order IN (first_stop, turn_sequence)
    GROUP BY vehicle_id, visit_id, stop_order
    HAVING count(*) FILTER (WHERE running_state = 2) > 0
       AND NOT bool_or(previous_at IS NULL)
), compared AS (
    SELECT *, lag(stop_order) OVER w AS previous_departure_stop,
           lag(departed_at) OVER w AS previous_departure_at,
           lag(stop_order, 2) OVER w AS two_departures_ago_stop,
           lag(departed_at, 2) OVER w AS two_departures_ago_at
    FROM departures
    WINDOW w AS (PARTITION BY vehicle_id ORDER BY departed_at, visit_id)
), intervals AS (
    SELECT vehicle_id, 'ONE_WAY_CANDIDATE' AS kind,
           previous_departure_stop AS from_stop, stop_order AS to_stop,
           previous_departure_at AS from_at, departed_at AS until_at
    FROM compared
    WHERE previous_departure_at IS NOT NULL AND stop_order <> previous_departure_stop
    UNION ALL
    SELECT vehicle_id, 'ROUND_TRIP_CANDIDATE', two_departures_ago_stop, stop_order,
           two_departures_ago_at, departed_at
    FROM compared
    WHERE two_departures_ago_stop = stop_order AND previous_departure_stop <> stop_order
    UNION ALL
    SELECT vehicle_id, 'SAME_TERMINAL_REAPPEARANCE', previous_departure_stop, stop_order,
           previous_departure_at, departed_at
    FROM compared
    WHERE previous_departure_stop = stop_order
), shortest AS (
    SELECT * FROM intervals
    WHERE kind = 'ROUND_TRIP_CANDIDATE'
    ORDER BY until_at - from_at, vehicle_id, from_at LIMIT 10
)
SELECT s.vehicle_id, s.from_stop, s.to_stop, s.from_at, s.until_at,
       extract(epoch FROM s.until_at - s.from_at) / 60.0 AS round_trip_minutes,
       count(o.id) AS observations_after_departure,
       max(extract(epoch FROM o.at - o.previous_at)) AS max_observation_gap_seconds
FROM shortest s
LEFT JOIN ordered o ON o.vehicle_id = s.vehicle_id AND o.at > s.from_at AND o.at <= s.until_at
GROUP BY s.vehicle_id, s.from_stop, s.to_stop, s.from_at, s.until_at
ORDER BY s.until_at - s.from_at, s.vehicle_id, s.from_at;

ROLLBACK;
