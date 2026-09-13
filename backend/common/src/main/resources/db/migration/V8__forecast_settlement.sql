-- 예보 행이 닫히는 규칙을 스키마가 지킨다. 라벨 회수를 배선하는 SAL-97 이 조인다.
--
-- V1__collector.sql 2번 줄이 값 범위 규칙(CHECK)은 그 규칙을 실제로 쓰는 티켓에서 조인다고 적었다.
-- scoring_state 와 라벨 세 열이 그 자리다. V3 은 열만 만들고 짝을 안 걸었다.
--
-- V1 ~ V7 은 고치지 않는다. Flyway 의 checksum 은 주석까지 포함해 계산해서, 한 글자만 바꿔도
-- 그 파일을 이미 돌린 DB 가 기동을 거부한다. 테스트는 매번 새 컨테이너라 CI 가 못 잡는다.
--
-- V7 은 비워 둔다. 적응형 수집(SAL-87)이 그 번호를 쓰기로 했다.
--
-- 두 표에 행이 하나도 없다는 전제로 짠다. 예보를 내는 코드가 여태 없어서 예보가 쌓인 적이 없다.

ALTER TABLE seat_forecast
    ADD CONSTRAINT ck_forecast_scoring_state_value
        CHECK (scoring_state IN ('PENDING', 'SETTLED', 'SKIPPED', 'LOST', 'SEAT_MISSING')),
    -- 도착 관측을 찾은 것은 회수함과 좌석 결측 둘뿐이다.
    -- 건너뜀과 끊김은 찾을 관측이 없어서 닫힌 것이라 가리킬 행이 없다
    ADD CONSTRAINT ck_forecast_arrival_observation_presence
        CHECK ((arrival_observation_id IS NOT NULL) = (scoring_state IN ('SETTLED', 'SEAT_MISSING'))),
    -- 도착 잔여석은 회수함일 때만 있다. 0 이면 만석이고 그것이 채점의 라벨이다
    ADD CONSTRAINT ck_forecast_seats_on_arrival_presence
        CHECK ((seats_on_arrival IS NOT NULL) = (scoring_state = 'SETTLED')),
    -- 상류의 음수는 "모른다"는 신호라 적재가 사유로 접는다. 여기 남는 값은 실제 좌석 수다
    ADD CONSTRAINT ck_forecast_seats_on_arrival_not_negative
        CHECK (seats_on_arrival >= 0),
    -- 아직 안 닫힌 행에만 회수 시각이 없다
    ADD CONSTRAINT ck_forecast_scored_at_presence
        CHECK ((scored_at IS NULL) = (scoring_state = 'PENDING'));

-- 라벨 회수 배치가 도는 질의. 부분 조건이 조회의 WHERE 와 글자 그대로 같아야 계획기가 이걸 고른다.
CREATE INDEX ix_forecast_awaiting_label
    ON seat_forecast (route_version_id, generated_at)
    WHERE scoring_state = 'PENDING';
