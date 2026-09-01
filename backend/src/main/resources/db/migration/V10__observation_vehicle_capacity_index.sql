-- 정원을 낼 때 그 차량이 지금까지 보여 준 최대 잔여석을 찾는 조회를 받는 인덱스.
--
-- 예보가 안 붙은 batch 마다 그 batch 의 차량들에 대해 관측 이력 전부를 다시 집계한다.
-- 이력은 지우지 않고 쌓이기만 해서 이 조회만 시간이 지날수록 느려진다.
--
-- 실측(관측 3,110,600행, 30일치 규모, 배속 103대 중 한 batch 에 18대)
--   인덱스 없음  574.9 ms  Parallel Seq Scan
--   인덱스 있음  198.8 ms  Bitmap Index Scan
-- 3일치 311,060행에서는 31.0 ms 와 23.1 ms 로 차이가 작다. 쌓일수록 벌어진다.
--
-- 부분 인덱스인 이유는 잔여석이 빈 행이 정원 산출에 안 들어가서다. 조회의 WHERE 와 같은 조건을
-- 걸어야 플래너가 이 인덱스를 고를 수 있다.
CREATE INDEX ix_observation_vehicle_capacity
    ON vehicle_observation (route_version_id, vehicle_id, remaining_seats)
    WHERE remaining_seats IS NOT NULL;
