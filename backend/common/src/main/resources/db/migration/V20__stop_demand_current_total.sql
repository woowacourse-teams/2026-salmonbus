-- 정원으로 나누기 전의 원합이다. 완료된 예보용 통계와 분리한다.
CREATE TABLE stop_demand_current_total (
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    vehicle_id varchar(40) NOT NULL,
    arrived_hour_start timestamptz NOT NULL,
    target_stop_order integer NOT NULL,
    sample_count bigint NOT NULL CHECK (sample_count > 0),
    arrival_seats_sum bigint NOT NULL CHECK (arrival_seats_sum >= 0),
    net_boarding_sum bigint NOT NULL,
    PRIMARY KEY (route_version_id, vehicle_id, arrived_hour_start, target_stop_order)
);
