-- 만석 확률을 오늘 성적으로 옮길 때 도는 조회를 받는 인덱스.
--
-- 예보를 낼 batch 마다 그날 이미 도착이 확인된 예보를 다시 집계한다. 예보 행은 지우지 않고
-- 쌓이기만 해서 이 조회만 시간이 지날수록 느려진다. 밀린 batch 를 몰아 처리할 때는 같은 범위를
-- 여러 번 다시 읽는다.
--
-- seat_forecast 에 이 조건을 받는 인덱스가 없었다. 기본키는 vehicle_observation_id 로 시작하고,
-- ix_forecast_awaiting_label 은 PENDING 행에만 걸려 있어서 SETTLED 를 찾는 데 못 쓴다.
--
-- 실측(예보 3,024,000행, 표 407MB, 30일 규모, batch 14,000개에 관측 252,000행)
--   인덱스 없음  404~424 ms  Parallel Seq Scan
--   인덱스 있음  104~111 ms  Index Scan
--   인덱스 크기  25MB. 표의 6퍼센트다
--
-- 조회를 도착 batch 쪽에서 시작하도록 뒤집는 것도 재봤는데 386~405 ms 로 거의 안 줄었다.
-- 인덱스가 없으면 어느 방향으로 짜든 예보 표를 통째로 훑는다. 그래서 조회는 그대로 두고
-- 인덱스만 얹는다.
--
-- 부분 인덱스인 이유는 아직 도착이 확인 안 된 행이 성적에 안 들어가서다. 조회의 WHERE 와 같은
-- 조건을 걸어야 계획기가 이 인덱스를 고른다.
CREATE INDEX ix_forecast_settled_arrival
    ON seat_forecast (arrival_observation_id)
    WHERE scoring_state = 'SETTLED' AND seats_on_arrival IS NOT NULL;
