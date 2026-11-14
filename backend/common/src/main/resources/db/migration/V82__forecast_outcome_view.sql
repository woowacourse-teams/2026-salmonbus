SET LOCAL lock_timeout = '5s';

-- 새 예보 경로의 예측(관측, 목표 정류장)마다 채점 결과를 보여 주는 뷰다. 테이블을 만들지 않고 줄을 쓰지 않는다.
--
-- 채점 단계는 짝짓기 조건(기준 시각 10초 뒤 도달, 2시간 안에 닫힘)을 이미 판정했고, 뷰는 그 결과를 그대로 보여 준다.
-- 한 연결·정류장의 통과는 하나뿐이고, 채점 단계는 통과가 닫히는 순간 그 통과를 기다리던 예측을 모두 닫는다.
-- 짝이 된 예측은 (모델 배포, 정원)마다 정답 한 줄(pass_label)을 받고, 짝이 되지 않았거나 채점할 수 없는 예측은
-- forecast_unresolved에 한 줄 남는다. 두 줄은 한 트랜잭션으로 함께 쓰인다. 그래서 상태는 다음 순서로 정한다.
--   1. forecast_unresolved에 줄이 있으면 그 상태(UNSUPPORTED, LOST)와 사유다.
--   2. 없으면 같은 노선·같은 연결 시작 관측·같은 정류장의 통과(stop_pass)에 이 예측의 정원·모델 배포로 계산한 정답이
--      있으면 그 정답의 상태(SETTLED, UNSUPPORTED)와 사유·값이다.
--   3. 둘 다 없으면 아직 채점 전(PENDING)이다.
-- 짝이 된 통과는 기준 시각 2시간 안에 닫히므로 통과의 나눔 날짜는 예보의 나눔 날짜이거나 그다음 날이다.
-- 정답 판은 새 채점이 쓰는 판(alighting-confirmed-capacity-snapshot-v3) 하나만 본다.
CREATE VIEW forecast_outcome AS
SELECT record.source_date,
       record.vehicle_observation_id,
       target.target_stop_order,
       record.route_id,
       record.route_version_id,
       record.vehicle_id,
       record.observation_batch_id,
       run.confirmed_at,
       target.expected_seats,
       target.full_probability,
       record.capacity,
       run.model_deployment_id,
       CASE
           WHEN unresolved.state IS NOT NULL THEN unresolved.state
           WHEN label.state IS NOT NULL THEN label.state
           ELSE 'PENDING'
       END AS state,
       coalesce(unresolved.reason, label.reason) AS reason,
       label.label_value,
       label.parameter_hash,
       label.arrival_observation_id,
       label.last_observation_id,
       label.closure_observation_id,
       label.previous_last_observation_id,
       label.previous_closure_observation_id
FROM forecast_record record
JOIN forecast_run run
    ON run.source_date = record.source_date AND run.observation_batch_id = record.observation_batch_id
CROSS JOIN LATERAL unnest(record.target_stop_orders, record.expected_seats, record.full_probabilities)
    AS target(target_stop_order, expected_seats, full_probability)
LEFT JOIN forecast_unresolved unresolved
    ON unresolved.source_date = record.source_date
   AND unresolved.vehicle_observation_id = record.vehicle_observation_id
   AND unresolved.target_stop_order = target.target_stop_order
LEFT JOIN LATERAL (
    SELECT pass_label.state, pass_label.reason, pass_label.label_value, pass_label.parameter_hash,
           stop_pass.arrival_observation_id, stop_pass.last_observation_id, stop_pass.closure_observation_id,
           pass_label.previous_last_observation_id, pass_label.previous_closure_observation_id
    FROM stop_pass
    JOIN pass_label
        ON pass_label.source_date = stop_pass.source_date
       AND pass_label.route_id = stop_pass.route_id
       AND pass_label.trip_start_observation_id = stop_pass.trip_start_observation_id
       AND pass_label.stop_order = stop_pass.stop_order
       AND pass_label.capacity = record.capacity
       AND pass_label.label_version = 'alighting-confirmed-capacity-snapshot-v3'
       AND pass_label.model_deployment_id = run.model_deployment_id
    WHERE unresolved.state IS NULL
      AND stop_pass.route_id = record.route_id
      AND stop_pass.chain_start_observation_id = record.chain_start_observation_id
      AND stop_pass.stop_order = target.target_stop_order
      AND stop_pass.source_date BETWEEN record.source_date AND record.source_date + 1
) label ON true;
