-- 관측 INSERT 를 배선하는 티켓이 값의 범위 규칙(CHECK)을 같이 조인다. SAL-84.
--
-- V1__collector.sql 2번 줄이 그 목록을 "SAL-82 · SAL-85 · SAL-88" 이라고 적고 있는데 SAL-84 가 빠져 있다.
-- V1 은 고치지 않는다. Flyway 의 checksum 은 주석까지 포함해 계산해서, 한 글자만 바꿔도
-- 그 파일을 이미 돌린 DB 가 기동을 거부한다. 테스트는 매번 새 컨테이너로 V1 부터 다시 돌아서
-- CI 가 그것을 못 잡는다. 그래서 V1 은 그대로 두고 빠진 몫을 여기에 적는다.
--
-- 두 표에 행이 하나도 없다는 전제로 NOT NULL 을 기본값 없이 붙인다.
-- VehicleObservation.from() 을 부르는 곳이 여태 없어서 관측이 쌓인 적이 없다.

-- observation_batch.response_received_at 과 늘 같은 값이라 중복이다.
-- 한 묶음에 딸린 행은 관측 시각이 다 같아서 행마다 들고 있을 이유가 없다.
ALTER TABLE vehicle_observation
    DROP COLUMN observed_at;

ALTER TABLE vehicle_observation
    -- 잔여석을 모를 때 왜 모르는지. 아는 값과 둘 중 하나만 있다
    ADD COLUMN seat_unknown_reason varchar(20),
    -- 이 버스가 지나온 정류소의 순번. 도착 중이면 그 정류소는 아직 안 지났다.
    -- 계산은 도메인이 하고 DB 생성 열은 안 쓴다. processor 는 collector 를 못 봐서 이 열이 유일한 통로다.
    -- 첫 정류소에 도착 중인 버스는 0 이다. 지나온 정류소가 없다는 뜻이라 정상이다
    ADD COLUMN passed_stop_order integer NOT NULL;

-- 뜻을 모르는 운행 상태의 관측은 저장 단계에서 행 단위로 뺀다.
-- 그런 행이 하나 들어오면 /vehicles 의 phase 가 ARRIVING · DEPARTED · IN_TRANSIT 셋으로
-- 고정이라 담을 자리가 없어서 그 노선 응답 전체가 500 으로 나간다.
-- 거르는 코드가 틀려도 조회가 500 이 안 나도록 DB 가 한 번 더 막는다.
ALTER TABLE vehicle_observation
    ALTER COLUMN running_state SET NOT NULL;

ALTER TABLE vehicle_observation
    ADD CONSTRAINT ck_observation_running_state_value
        CHECK (running_state IN (0, 1, 2)),
    -- 잔여석은 아는 값이거나 모르는 사유이고, 둘 다이거나 둘 다 아닌 행은 없다
    ADD CONSTRAINT ck_observation_seats_exclusive_with_reason
        CHECK ((remaining_seats IS NULL) <> (seat_unknown_reason IS NULL)),
    -- 상류가 주는 음수는 "모른다"는 신호라 정규화가 사유로 접는다. 여기 남는 값은 실제 좌석 수다
    ADD CONSTRAINT ck_observation_seats_not_negative
        CHECK (remaining_seats >= 0),
    ADD CONSTRAINT ck_observation_seat_unknown_reason_value
        CHECK (seat_unknown_reason IN ('REPORTED_UNKNOWN', 'NOT_REPORTED')),
    -- 상류의 0 은 미제공이라 정규화가 비운다. 여기 남는 값은 실제 혼잡도다
    ADD CONSTRAINT ck_observation_crowd_level_range
        CHECK (crowd_level BETWEEN 1 AND 4);

-- 어느 정규화 규칙으로 접힌 값인가. 원문을 따로 안 남겨서 vehicle_observation 이 사실상 원본이고,
-- 이 값이 있어야 나중에 어느 규칙의 산출물인지 되짚는다.
-- 한 묶음의 행은 같은 규칙으로 접히므로 판 단위로 들고 있는다.
ALTER TABLE observation_batch
    ADD COLUMN normalization_version varchar(40) NOT NULL;
