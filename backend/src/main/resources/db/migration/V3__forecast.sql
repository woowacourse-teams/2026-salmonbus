CREATE TABLE demand_profile (
    route_reference_version_id  bigint           NOT NULL,
    stop_order                  integer          NOT NULL,
    time_cell_id                varchar(40)      NOT NULL,
    feature_contract_version    varchar(40)      NOT NULL,
    revision                    integer          NOT NULL,
    occupancy_mean              double precision NOT NULL,
    net_demand_mean             double precision NOT NULL,
    sample_count                integer          NOT NULL,
    day_count                   integer          NOT NULL,
    trained_through             timestamptz      NOT NULL,
    computed_at                 timestamptz      NOT NULL,
    CONSTRAINT pk_demand_profile PRIMARY KEY (route_reference_version_id, stop_order, time_cell_id, feature_contract_version),
    CONSTRAINT fk_profile_route_stop FOREIGN KEY (route_reference_version_id, stop_order)
        REFERENCES route_stop (route_reference_version_id, stop_order),
    CONSTRAINT ck_profile_revision_positive CHECK (revision >= 1),
    CONSTRAINT ck_profile_occupancy_mean CHECK (occupancy_mean BETWEEN 0 AND 1),
    CONSTRAINT ck_profile_net_demand_mean CHECK (net_demand_mean BETWEEN -1 AND 1),
    CONSTRAINT ck_profile_sample_count_non_negative CHECK (sample_count >= 0),
    CONSTRAINT ck_profile_day_count_within_samples CHECK (day_count BETWEEN 0 AND sample_count)
);

CREATE TABLE vehicle_stop_prediction (
    vehicle_observation_id      bigint           NOT NULL,
    route_reference_version_id  bigint           NOT NULL,
    target_stop_order           integer          NOT NULL,
    horizon_stops               integer          NOT NULL,
    model_deployment_id         bigint           NOT NULL,
    cell_profile_revision       integer          NOT NULL,
    p_full_raw                  double precision NOT NULL,
    p_full                      double precision NOT NULL,
    expected_seats              double precision,
    generated_at                timestamptz      NOT NULL,
    settlement                  varchar(16)      NOT NULL,
    outcome_observation_id      bigint,
    arrived_seats               integer,
    settled_at                  timestamptz,
    CONSTRAINT pk_vehicle_stop_prediction PRIMARY KEY (vehicle_observation_id, target_stop_order),
    CONSTRAINT fk_vsp_obs FOREIGN KEY (vehicle_observation_id, route_reference_version_id)
        REFERENCES vehicle_observation (id, route_reference_version_id),
    CONSTRAINT fk_vsp_target_stop FOREIGN KEY (route_reference_version_id, target_stop_order)
        REFERENCES route_stop (route_reference_version_id, stop_order),
    CONSTRAINT fk_vsp_deployment FOREIGN KEY (model_deployment_id)
        REFERENCES model_deployment (id),
    CONSTRAINT fk_vsp_outcome FOREIGN KEY (outcome_observation_id)
        REFERENCES vehicle_observation (id),
    CONSTRAINT ck_vsp_target_stop_order_positive CHECK (target_stop_order >= 1),
    CONSTRAINT ck_vsp_horizon CHECK (horizon_stops BETWEEN 1 AND 12),
    CONSTRAINT ck_vsp_cell_profile_revision_positive CHECK (cell_profile_revision >= 1),
    CONSTRAINT ck_vsp_p_full_raw_range CHECK (p_full_raw BETWEEN 0 AND 1),
    CONSTRAINT ck_vsp_p_full_range CHECK (p_full BETWEEN 0 AND 1),
    CONSTRAINT ck_vsp_expected_seats_non_negative CHECK (expected_seats IS NULL OR expected_seats >= 0),
    CONSTRAINT ck_vsp_settlement CHECK (settlement IN ('PENDING', 'SETTLED', 'SKIPPED', 'LOST', 'SEAT_MISSING')),
    CONSTRAINT ck_vsp_outcome_obs_matches_settlement CHECK (
        (settlement IN ('SETTLED', 'SEAT_MISSING') AND outcome_observation_id IS NOT NULL)
        OR (settlement NOT IN ('SETTLED', 'SEAT_MISSING') AND outcome_observation_id IS NULL)
    ),
    CONSTRAINT ck_vsp_arrived_seats_matches_settlement CHECK (
        (settlement = 'SETTLED' AND arrived_seats IS NOT NULL AND arrived_seats >= 0)
        OR (settlement <> 'SETTLED' AND arrived_seats IS NULL)
    ),
    CONSTRAINT ck_vsp_settled_at_matches_settlement CHECK (
        (settlement = 'PENDING' AND settled_at IS NULL)
        OR (settlement <> 'PENDING' AND settled_at IS NOT NULL AND generated_at <= settled_at)
    )
);
