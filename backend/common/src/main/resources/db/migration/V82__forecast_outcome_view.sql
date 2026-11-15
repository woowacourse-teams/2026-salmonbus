SET LOCAL lock_timeout = '5s';

-- 예측(관측, 목표 정류장)마다 채점 결과를 보여 주는 뷰다. 테이블을 만들지 않고 줄을 쓰지 않는다.
--
-- 한 연결·정류장의 통과는 하나뿐이고, 채점 단계는 통과가 닫히는 순간 그 통과를 기다리던 예측을 모두 닫는다.
-- 짝이 된 예측은 (모델 배포, 정원)마다 정답 한 줄(pass_label)을 받고, 짝이 되지 않았거나 채점할 수 없는 예측은
-- forecast_unresolved에 한 줄 남는다.
--
-- 짝짓기 조건은 채점 단계의 판정과 같고 경계도 같다.
--   * 통과의 차량이 예측의 차량과 같다.
--   * 통과가 닫힌 시각이 예측 기준 시각(착수 시각) + 2시간 이하다.
--   * 목표에 정확히 도달한 좌석 있는 관측의 시각(reached_known_seat_at)이 있고 기준 시각 + 10초보다 늦다.
-- 상태는 다음 순서로 정한다.
--   1. forecast_unresolved에 줄이 있으면 그 상태(UNSUPPORTED, LOST)와 사유다.
--   2. 없으면 같은 노선·같은 연결 시작 관측·같은 정류장의 통과(stop_pass)에 이 예측의 정원·모델 배포로 계산한 정답이 있는지 본다.
--      짝짓기 조건이 맞으면 그 정답의 상태(SETTLED, UNSUPPORTED)와 사유·값이다.
--      맞지 않으면 놓침(LOST, PASS_CONDITION_UNMET)이고 정답 값과 근거 관측 열은 비운다. 통과는 닫혔지만 조건에 맞지 않는
--      예측의 미해결 줄이 아직 없을 때(채점 결과를 쓰기 전에 프로세스가 끝난 경우 등) 같은 통과의 다른 정답이 붙지 않게 한다.
--   3. 그 밖에는 아직 채점 전(PENDING)이다. 통과가 닫혔어도 그 통과의 정답이 아직 없으면 여기에 든다.
-- 짝이 된 통과는 기준 시각 2시간 안에 닫히므로 통과의 나눔 날짜는 예보의 나눔 날짜이거나 그다음 날이다.
-- 정답 판은 채점이 쓰는 판(alighting-confirmed-capacity-snapshot-v3) 하나만 본다.
--
-- 짝짓기 조건(paired)은 통과·정답을 찾는 안쪽 조인이 아니라 그 바깥에서 계산한다. 안쪽 조인의 선택 목록에 예보 쪽 열을 쓰는 식을
-- 두면 플래너가 통과·정답을 예측마다 따로 찾는 계획만 고를 수 있어, 한꺼번에 묶어 읽는 계획을 쓰지 못한다.
CREATE VIEW forecast_outcome AS
SELECT judged.source_date,
       judged.vehicle_observation_id,
       judged.target_stop_order,
       judged.route_id,
       judged.route_version_id,
       judged.vehicle_id,
       judged.observation_batch_id,
       judged.confirmed_at,
       judged.expected_seats,
       judged.full_probability,
       judged.capacity,
       judged.model_deployment_id,
       CASE
           WHEN judged.unresolved_state IS NOT NULL THEN judged.unresolved_state
           WHEN judged.paired THEN judged.label_state
           WHEN NOT judged.paired THEN 'LOST'
           ELSE 'PENDING'
       END AS state,
       (CASE
           WHEN judged.unresolved_state IS NOT NULL THEN judged.unresolved_reason
           WHEN judged.paired THEN judged.label_reason
           WHEN NOT judged.paired THEN 'PASS_CONDITION_UNMET'
       END)::varchar(64) AS reason,
       CASE WHEN judged.paired THEN judged.label_value END AS label_value,
       (CASE WHEN judged.paired THEN judged.parameter_hash END)::char(64) AS parameter_hash,
       CASE WHEN judged.paired THEN judged.arrival_observation_id END AS arrival_observation_id,
       CASE WHEN judged.paired THEN judged.last_observation_id END AS last_observation_id,
       CASE WHEN judged.paired THEN judged.closure_observation_id END AS closure_observation_id,
       CASE WHEN judged.paired THEN judged.previous_last_observation_id END AS previous_last_observation_id,
       CASE WHEN judged.paired THEN judged.previous_closure_observation_id END AS previous_closure_observation_id
FROM (
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
           unresolved.state AS unresolved_state,
           unresolved.reason AS unresolved_reason,
           label.state AS label_state,
           label.reason AS label_reason,
           label.label_value,
           label.parameter_hash,
           label.arrival_observation_id,
           label.last_observation_id,
           label.closure_observation_id,
           label.previous_last_observation_id,
           label.previous_closure_observation_id,
           -- 정답이 있는 통과를 찾았을 때만 참·거짓이고, 찾지 못했으면 비어 있다.
           CASE
               WHEN label.state IS NOT NULL THEN
                   label.vehicle_id = record.vehicle_id
                   AND label.closed_at <= run.confirmed_at + interval '2 hours'
                   AND label.reached_known_seat_at IS NOT NULL
                   AND label.reached_known_seat_at > run.confirmed_at + interval '10 seconds'
           END AS paired
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
               pass_label.previous_last_observation_id, pass_label.previous_closure_observation_id,
               stop_pass.vehicle_id, stop_pass.closed_at, stop_pass.reached_known_seat_at
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
    ) label ON true
) judged;
