-- 셀 통계 세대를 덮어쓰지 않고 쌓는다.
--
-- 종전 기본키에 revision 이 없어서 한 (노선 판본, 정류장, 시간대, 계산 규칙 판) 에 세대 하나만
-- 있을 수 있었다. 그래서 새 세대를 넣으려면 옛 세대를 지워야 했고, 밀린 batch 를 뒤늦게 처리하면
-- 그 관측 시각보다 뒤의 라벨까지 들어간 셀을 읽었다. 같은 batch 를 다시 처리하면 값이 달라진다.
--
-- 기본키에 revision 을 넣으면 세대가 나란히 남는다. 예보는 자기 관측 시각까지의 자료로 낸
-- 세대를 고른다. data_until 열이 그 기준이고 이미 있다.
ALTER TABLE stop_demand_statistics
    DROP CONSTRAINT pk_stop_demand_statistics;

ALTER TABLE stop_demand_statistics
    ADD CONSTRAINT pk_stop_demand_statistics
        PRIMARY KEY (route_version_id, stop_order, time_slot, calculation_version, revision);

-- 세대가 쌓이지만 양이 작다. 집계가 6시간마다 돌고 한 세대가 정류장 60 × 시간대 3 = 180행이라
-- 하루 720행, 1년 26만 행이다. 지우는 배치는 안 만들었다. 부를 자리가 아직 없다.
--
-- 관측 시각까지의 자료로 낸 세대 중 가장 최근 것을 고르는 조회를 받는다.
CREATE INDEX ix_statistics_generation_as_of
    ON stop_demand_statistics (route_version_id, calculation_version, data_until, revision);
