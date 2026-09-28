-- 정산과 같은 transaction에서 기록하고 통계에 반영한 뒤 지울 처리 대상이다.
-- 기존 SETTLED 이력은 여기서 일괄 적재하지 않는다. 초기 집계 이관은 별도 작업이다.
-- id는 행 식별자일 뿐 commit 순서나 과거 입력의 완전성을 뜻하지 않는다.
CREATE TABLE stop_demand_pending_sample (
    id                         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    route_version_id           bigint NOT NULL REFERENCES route_version(id),
    prediction_observation_id  bigint NOT NULL REFERENCES vehicle_observation(id),
    arrival_observation_id     bigint NOT NULL REFERENCES vehicle_observation(id),
    vehicle_id                 varchar(40) NOT NULL,
    target_stop_order          integer NOT NULL,
    arrived_at                 timestamptz NOT NULL,
    scored_at                  timestamptz NOT NULL,
    prediction_remaining_seats integer NOT NULL CHECK (prediction_remaining_seats >= 0),
    arrival_remaining_seats    integer NOT NULL CHECK (arrival_remaining_seats >= 0),
    recorded_at                timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_stop_demand_pending_sample_vehicle
    ON stop_demand_pending_sample (route_version_id, vehicle_id, id);
