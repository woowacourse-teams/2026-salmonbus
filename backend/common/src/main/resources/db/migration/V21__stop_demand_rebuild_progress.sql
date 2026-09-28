CREATE TABLE stop_demand_baseline (
    route_version_id bigint PRIMARY KEY REFERENCES route_version(id),
    initialized boolean NOT NULL DEFAULT false,
    data_until timestamptz
);
CREATE TABLE stop_demand_rebuild_progress (
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    vehicle_id varchar(40) NOT NULL,
    request_id uuid NOT NULL,
    quality_revision bigint NOT NULL,
    data_until timestamptz NOT NULL,
    input_until_id bigint NOT NULL,
    observation_until_id bigint NOT NULL,
    cursor_id bigint NOT NULL DEFAULT 0,
    phase varchar(16) NOT NULL CHECK (phase IN ('SCAN','CLEAR','COPY','ACK','CLEAN')),
    PRIMARY KEY(route_version_id, vehicle_id)
);
CREATE TABLE stop_demand_rebuild_total (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    scope_vehicle_id varchar(40) NOT NULL,
    request_id uuid NOT NULL,
    vehicle_id varchar(40) NOT NULL,
    arrived_hour_start timestamptz NOT NULL,
    target_stop_order integer NOT NULL,
    sample_count bigint NOT NULL,
    arrival_seats_sum bigint NOT NULL,
    net_boarding_sum bigint NOT NULL,
    UNIQUE(request_id, vehicle_id, arrived_hour_start, target_stop_order)
);
CREATE INDEX ix_demand_rebuild_page ON stop_demand_rebuild_total(request_id, id);
CREATE INDEX ix_demand_rebuild_scope ON stop_demand_rebuild_total(route_version_id, scope_vehicle_id, id);
CREATE INDEX ix_demand_pending_source ON stop_demand_pending_sample(prediction_observation_id, target_stop_order);
CREATE INDEX ix_demand_pending_route ON stop_demand_pending_sample(route_version_id, id);
