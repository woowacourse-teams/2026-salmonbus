-- 2026-09-21 사용자 제공 조회 범위. 노선 1650=판본2, 3330=판본1.
-- 원본 테이블 읽기 전용. 상태 표시 반복은 정류소 방문 단위로 합친다.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '15s';
SET LOCAL max_parallel_workers_per_gather = 0;
SET LOCAL work_mem = '8MB';
SET LOCAL TIME ZONE 'Asia/Seoul';

WITH routes AS (
    SELECT v.id, r.display_name AS route_name, v.turn_sequence,
           min(s.stop_order) AS first_stop
    FROM route_version v
    JOIN route r ON r.id = v.route_id
    JOIN route_stop s ON s.route_version_id = v.id
    WHERE v.id IN (1, 2)
    GROUP BY v.id, r.display_name, v.turn_sequence
), observations AS (
    SELECT b.route_version_id, o.vehicle_id, o.stop_order, o.running_state,
           b.response_received_at AS at, b.id AS batch_id, o.id,
           r.first_stop, r.turn_sequence,
           lag(o.stop_order) OVER w AS previous_stop
    FROM observation_batch b
    JOIN vehicle_observation o
      ON o.observation_batch_id = b.id AND o.route_version_id = b.route_version_id
    JOIN routes r ON r.id = b.route_version_id
    WHERE b.route_version_id IN (1, 2)
      AND b.response_received_at >= timestamptz '2026-09-21 11:22:00+09'
      AND b.response_received_at < timestamptz '2026-09-21 17:22:18+09'
      AND o.vehicle_id IS NOT NULL
    WINDOW w AS (
        PARTITION BY b.route_version_id, o.vehicle_id
        ORDER BY b.response_received_at, b.id, o.id
    )
), visits AS (
    SELECT *, sum(CASE WHEN previous_stop IS DISTINCT FROM stop_order THEN 1 ELSE 0 END)
        OVER (PARTITION BY route_version_id, vehicle_id
              ORDER BY at, batch_id, id ROWS UNBOUNDED PRECEDING) AS visit_id
    FROM observations
), departures AS (
    SELECT route_version_id, vehicle_id, visit_id, stop_order,
           min(at) FILTER (WHERE running_state = 2) AS departed_at
    FROM visits
    WHERE stop_order IN (first_stop, turn_sequence)
    GROUP BY route_version_id, vehicle_id, visit_id, stop_order
    HAVING count(*) FILTER (WHERE running_state = 2) > 0
       AND visit_id > 1
), compared AS (
    SELECT *, lag(stop_order) OVER w AS previous_stop,
           lag(departed_at) OVER w AS previous_at,
           lag(stop_order, 2) OVER w AS two_ago_stop,
           lag(departed_at, 2) OVER w AS two_ago_at
    FROM departures
    WINDOW w AS (
        PARTITION BY route_version_id, vehicle_id ORDER BY departed_at, visit_id
    )
), intervals AS (
    SELECT route_version_id, 'ONE_WAY' AS kind,
           extract(epoch FROM departed_at - previous_at) / 60.0 AS minutes
    FROM compared WHERE previous_stop <> stop_order
    UNION ALL
    SELECT route_version_id, 'ROUND_TRIP',
           extract(epoch FROM departed_at - two_ago_at) / 60.0
    FROM compared WHERE two_ago_stop = stop_order AND previous_stop <> stop_order
    UNION ALL
    SELECT route_version_id, 'SAME_TERMINAL_ONLY',
           extract(epoch FROM departed_at - previous_at) / 60.0
    FROM compared WHERE previous_stop = stop_order
)
SELECT r.route_name, k.kind,
       (SELECT count(*) FROM departures d WHERE d.route_version_id = r.id) AS departure_events,
       count(i.minutes) AS samples,
       round(min(i.minutes), 2) AS min_minutes,
       round((percentile_cont(0.1) WITHIN GROUP (ORDER BY i.minutes))::numeric, 2) AS p10_minutes,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY i.minutes))::numeric, 2) AS p50_minutes,
       round(max(i.minutes), 2) AS max_minutes
FROM routes r
CROSS JOIN (VALUES ('ONE_WAY'), ('ROUND_TRIP'), ('SAME_TERMINAL_ONLY')) k(kind)
LEFT JOIN intervals i ON i.route_version_id = r.id AND i.kind = k.kind
GROUP BY r.id, r.route_name, k.kind
ORDER BY r.route_name, k.kind;

ROLLBACK;
