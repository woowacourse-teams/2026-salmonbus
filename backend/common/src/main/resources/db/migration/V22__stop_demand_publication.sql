CREATE TABLE stop_demand_vehicle (
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    vehicle_id varchar(40) NOT NULL,
    PRIMARY KEY(route_version_id,vehicle_id)
);
CREATE TABLE stop_demand_run (
    route_version_id bigint PRIMARY KEY REFERENCES route_version(id),
    run_id uuid NOT NULL,
    quality_revision bigint NOT NULL,
    phase varchar(16) NOT NULL CHECK (phase IN ('STALE','CLEAN','CAPTURE','ACCUMULATE','FOLD','REDUCE','PUBLISH','DONE')),
    data_until timestamptz NOT NULL,
    input_until_id bigint NOT NULL DEFAULT 0,
    vehicle_cursor varchar(40) NOT NULL DEFAULT '',
    cursor_id bigint NOT NULL DEFAULT 0,
    hour_cursor timestamptz NOT NULL DEFAULT '-infinity',
    stop_cursor integer NOT NULL DEFAULT 0,
    slot_cursor varchar(40) NOT NULL DEFAULT '',
    day_cursor date NOT NULL DEFAULT '-infinity',
    completed_at timestamptz
);
CREATE TABLE stop_demand_capacity_stage (
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    vehicle_id varchar(40) NOT NULL,
    capacity integer NOT NULL CHECK(capacity>=1),
    PRIMARY KEY(route_version_id,vehicle_id)
);
CREATE TABLE stop_demand_day_stage (
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    stop_order integer NOT NULL,
    time_slot varchar(40) NOT NULL,
    arrival_date date NOT NULL,
    fill_rate_total double precision NOT NULL,
    net_boarding_total bigint NOT NULL,
    capacity_total bigint NOT NULL,
    sample_count bigint NOT NULL,
    PRIMARY KEY(route_version_id,stop_order,time_slot,arrival_date)
);
CREATE TABLE stop_demand_cell_stage (
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    stop_order integer NOT NULL,
    time_slot varchar(40) NOT NULL,
    fill_rate_total double precision NOT NULL,
    net_boarding_rate_total double precision NOT NULL,
    sample_count bigint NOT NULL,
    day_count integer NOT NULL,
    PRIMARY KEY(route_version_id,stop_order,time_slot)
);
-- 0개 셀로 끝난 세대도 표현한다. 실제 통계 행과 같은 transaction으로만 기록한다.
CREATE TABLE stop_demand_publication (
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    calculation_version varchar(40) NOT NULL,
    revision integer NOT NULL,
    data_until timestamptz NOT NULL,
    computed_at timestamptz NOT NULL,
    quality_revision bigint NOT NULL,
    PRIMARY KEY(route_version_id,calculation_version,revision)
);
CREATE INDEX ix_demand_publication_asof ON stop_demand_publication(route_version_id,calculation_version,data_until,revision);
