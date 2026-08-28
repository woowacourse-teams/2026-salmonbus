-- 궤적 조회가 도는 질의 둘에 인덱스를 건다. SAL-94.
--
-- V5 는 이 파일에 없다. SAL-85(호출 장부)가 같은 시각에 V5 를 쓰고 있어서 번호를 나눠 가졌다.
-- 두 브랜치가 합쳐질 때 V5 가 앞에 들어온다.
--
-- 둘 다 observation_batch 에 건다. 관측을 판 단위로 읽어서
-- vehicle_observation 쪽은 ux_observation_source_row 의 첫 열로 이미 닿는다.
--
-- 둘 다 계획기가 실제로 고르는 것을 봤다. postgres:18 에 판 100,000행(노선 판본 50 × 판 2,000)을
-- 넣고 EXPLAIN ANALYZE 를 돌렸다. 대기 판 조회는 2,000행 중 3행을 10버퍼로, 이력 창 조회는
-- 30분치 80행을 3버퍼로 집었다. 다만 이건 합성 데이터다. 실제 분포는 SAL-87 이 수집을 돌린 뒤에 봐야 한다.

-- 예보를 아직 안 붙인 판을 오래된 것부터 찾는다.
-- 부분 인덱스로 두면 예보가 끝난 판이 인덱스에서 빠져서, 관측이 아무리 쌓여도 이 인덱스는 작게 남는다.
-- V1 의 ix_batch_forecast_ready 가 정확히 반대쪽(예보가 끝난 판)을 맡고 있다.
CREATE INDEX ix_batch_awaiting_forecast
    ON observation_batch (route_version_id, response_received_at)
    WHERE forecast_completed_at IS NULL;

-- 궤적을 이으려고 한 노선 판본의 최근 판을 시간 창으로 훑는다.
-- 예보가 끝난 판까지 다 봐야 해서 위의 부분 인덱스로는 못 닿는다.
CREATE INDEX ix_batch_recent_history
    ON observation_batch (route_version_id, response_received_at);
