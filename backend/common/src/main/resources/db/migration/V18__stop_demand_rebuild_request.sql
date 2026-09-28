-- 빈 vehicle_id는 수동 전체 판정처럼 노선 판본 전체를 정정해야 하는 요청이다.
CREATE TABLE stop_demand_rebuild_request (
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    vehicle_id varchar(40) NOT NULL,
    request_id uuid NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (route_version_id, vehicle_id)
);
