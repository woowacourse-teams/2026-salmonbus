-- 궤적 조회가 도는 질의 둘에 인덱스를 건다. SAL-94.
--
-- 번호는 머지 순서를 따라간다. 이 판본이 V4(관측 적재) 다음, V6(호출 장부) 앞에 들어간다.
-- Flyway 기본값 outOfOrder=false 는 번호를 건너뛴 판본을 나중에 못 돌린다. 이미 V6 을 돌린 DB 에
-- V5 가 뒤늦게 오면 validate 에서 걸려 기동이 막힌다. 테스트는 매번 새 컨테이너라 그 경로를 못 잡는다.
--
-- 둘 다 observation_batch 에 건다. 관측을 판 단위로 읽어서
-- vehicle_observation 쪽은 ux_observation_source_row 의 첫 열로 이미 닿는다.
--
-- 둘 다 계획기가 실제로 고르는 것을 봤다. postgres:18 에 판 550,000행을 넣고 EXPLAIN ANALYZE 를 돌렸다.
-- 다만 이건 합성 데이터다. 실제 분포는 SAL-87 이 수집을 돌린 뒤에 봐야 한다.

-- 예보를 아직 안 붙인 판을 오래된 것부터 찾는다.
-- V1 의 ix_batch_forecast_ready 가 정확히 반대쪽(예보가 끝난 판)을 맡고 있다.
--
-- 조건을 조회와 글자 그대로 같게 맞춘다. forecast_completed_at IS NULL 만 걸면
-- 예보 대상이 아닌 판까지 인덱스에 남는다. 성공한 판은 예보가 끝나면서 빠지는데
-- 실패한 판은 예보를 아예 안 받아서 안 빠지고, 시간이 갈수록 인덱스가 실패한 판으로만 찬다.
CREATE INDEX ix_batch_awaiting_forecast
    ON observation_batch (route_version_id, response_received_at)
    WHERE forecast_completed_at IS NULL
      AND response_received_at IS NOT NULL
      AND outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY');

-- 궤적을 이으려고 한 노선 판본의 최근 판을 시간 창으로 훑는다.
-- 예보가 끝난 판까지 다 봐야 해서 위의 부분 인덱스로는 못 닿는다.
CREATE INDEX ix_batch_recent_history
    ON observation_batch (route_version_id, response_received_at);
