CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE call_ledger (
    quota_provider  varchar(30) NOT NULL,
    quota_scope_id  varchar(60) NOT NULL,
    kst_date        date        NOT NULL,
    reserved_calls  integer     NOT NULL DEFAULT 0,
    daily_limit     integer     NOT NULL,
    CONSTRAINT pk_call_ledger PRIMARY KEY (quota_provider, quota_scope_id, kst_date),
    CONSTRAINT ck_ledger_reserved_within_limit CHECK (reserved_calls BETWEEN 0 AND daily_limit)
);

CREATE TABLE route_reference_version (
    id                          bigserial    NOT NULL,
    reference_version_id        varchar(60)  NOT NULL,
    public_route_id             varchar(30)  NOT NULL,
    source_id                   varchar(40)  NOT NULL,
    source_route_id             varchar(30)  NOT NULL,
    display_name                varchar(40)  NOT NULL,
    start_stop_name             varchar(60)  NOT NULL,
    end_stop_name               varchar(60)  NOT NULL,
    turn_sequence               integer,
    up_first_departure_time     varchar(5),
    up_last_departure_time      varchar(5),
    down_first_departure_time   varchar(5),
    down_last_departure_time    varchar(5),
    content_digest              char(64)     NOT NULL,
    valid_from                  timestamptz  NOT NULL,
    valid_to                    timestamptz,
    CONSTRAINT pk_route_reference_version PRIMARY KEY (id),
    CONSTRAINT ux_rrv_reference_version_id UNIQUE (reference_version_id),
    CONSTRAINT ux_rrv_route_content UNIQUE (public_route_id, content_digest),
    CONSTRAINT ux_rrv_route_identity UNIQUE (id, source_id, source_route_id, public_route_id),
    CONSTRAINT ck_rrv_turn_sequence_positive CHECK (turn_sequence IS NULL OR turn_sequence >= 1),
    CONSTRAINT ck_rrv_validity_ordered CHECK (valid_to IS NULL OR valid_from < valid_to),
    CONSTRAINT ck_rrv_up_first_departure_time CHECK (up_first_departure_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
    CONSTRAINT ck_rrv_up_last_departure_time CHECK (up_last_departure_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
    CONSTRAINT ck_rrv_down_first_departure_time CHECK (down_first_departure_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
    CONSTRAINT ck_rrv_down_last_departure_time CHECK (down_last_departure_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
    CONSTRAINT ex_rrv_validity_no_overlap EXCLUDE USING gist (
        public_route_id WITH =,
        tstzrange(valid_from, valid_to) WITH &&
    )
);

CREATE TABLE route_stop (
    route_reference_version_id  bigint      NOT NULL,
    stop_order                  integer     NOT NULL,
    stop_id                     varchar(20) NOT NULL,
    name                        varchar(60) NOT NULL,
    direction                   varchar(4)  NOT NULL,
    boarding_allowed            boolean     NOT NULL,
    CONSTRAINT pk_route_stop PRIMARY KEY (route_reference_version_id, stop_order),
    CONSTRAINT fk_route_stop_version FOREIGN KEY (route_reference_version_id)
        REFERENCES route_reference_version (id),
    CONSTRAINT ux_route_stop_context UNIQUE (route_reference_version_id, stop_order, stop_id),
    CONSTRAINT ck_route_stop_order_positive CHECK (stop_order >= 1),
    CONSTRAINT ck_route_stop_direction CHECK (direction IN ('UP', 'DOWN'))
);

CREATE TABLE location_poll (
    id                          bigserial    NOT NULL,
    source_id                   varchar(40)  NOT NULL,
    source_route_id             varchar(30)  NOT NULL,
    public_route_id             varchar(30)  NOT NULL,
    route_reference_version_id  bigint       NOT NULL,
    collection_strategy_version varchar(30)  NOT NULL,
    source_contract_version     varchar(30)  NOT NULL,
    normalization_version       integer      NOT NULL,
    scheduled_at                timestamptz  NOT NULL,
    attempt_no                  integer      NOT NULL,
    stable_attempt_key          varchar(160) NOT NULL,
    requested_at                timestamptz  NOT NULL,
    response_received_at        timestamptz,
    forecast_completed_at       timestamptz,
    completed_at                timestamptz,
    http_status                 integer,
    result_code                 integer,
    outcome                     varchar(32)  NOT NULL,
    failure_code                varchar(32),
    provider_rows               integer,
    stored_rows                 integer,
    excluded_rows               integer,
    evidence_payload_sha256     char(64),
    completion_digest           char(64),
    fence_version               integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_location_poll PRIMARY KEY (id),
    CONSTRAINT fk_poll_route_version FOREIGN KEY (route_reference_version_id, source_id, source_route_id, public_route_id)
        REFERENCES route_reference_version (id, source_id, source_route_id, public_route_id),
    CONSTRAINT ux_poll_attempt UNIQUE (source_id, stable_attempt_key),
    CONSTRAINT ux_poll_context UNIQUE (id, route_reference_version_id),
    CONSTRAINT ck_poll_outcome CHECK (outcome IN (
        'RESERVED', 'DISPATCHING', 'SUCCESS_ROWS', 'SUCCESS_EMPTY', 'BUSINESS_ERROR',
        'INCOMPLETE_ENVELOPE', 'TRANSPORT_ERROR', 'ABANDONED_BEFORE_SEND', 'UNKNOWN_AFTER_DISPATCH'
    )),
    CONSTRAINT ck_poll_completed_at_matches_outcome CHECK (
        (outcome IN ('RESERVED', 'DISPATCHING') AND completed_at IS NULL)
        OR (outcome NOT IN ('RESERVED', 'DISPATCHING') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_poll_rows_sum CHECK (
        provider_rows IS NULL OR stored_rows IS NULL OR excluded_rows IS NULL
        OR provider_rows = stored_rows + excluded_rows
    ),
    CONSTRAINT ck_poll_attempt_no_positive CHECK (attempt_no >= 1),
    CONSTRAINT ck_poll_forecast_needs_response CHECK (
        forecast_completed_at IS NULL OR response_received_at IS NOT NULL
    ),
    CONSTRAINT ck_poll_rows_non_negative CHECK (
        (provider_rows IS NULL OR provider_rows >= 0)
        AND (stored_rows IS NULL OR stored_rows >= 0)
        AND (excluded_rows IS NULL OR excluded_rows >= 0)
    )
);

CREATE INDEX ix_poll_forecast_ready
    ON location_poll (route_reference_version_id, response_received_at DESC)
    WHERE outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY') AND forecast_completed_at IS NOT NULL;

CREATE TABLE vehicle_observation (
    id                          bigserial    NOT NULL,
    poll_id                     bigint       NOT NULL,
    source_id                   varchar(40)  NOT NULL,
    source_route_id             varchar(30)  NOT NULL,
    public_route_id             varchar(30)  NOT NULL,
    route_reference_version_id  bigint       NOT NULL,
    source_row_no               integer      NOT NULL,
    observed_at                 timestamptz  NOT NULL,
    vehicle_id                  varchar(40),
    vehicle_trip_key            varchar(120),
    plate_number                varchar(20),
    stop_order                  integer      NOT NULL,
    stop_id                     varchar(20)  NOT NULL,
    phase                       varchar(12)  NOT NULL,
    remaining_seats             integer,
    seat_unavailable_reason     varchar(16),
    crowded_code                integer,
    low_plate_code              integer,
    CONSTRAINT pk_vehicle_observation PRIMARY KEY (id),
    CONSTRAINT fk_obs_poll FOREIGN KEY (poll_id, route_reference_version_id)
        REFERENCES location_poll (id, route_reference_version_id),
    CONSTRAINT fk_obs_route_stop FOREIGN KEY (route_reference_version_id, stop_order, stop_id)
        REFERENCES route_stop (route_reference_version_id, stop_order, stop_id),
    CONSTRAINT ux_obs_source_row UNIQUE (poll_id, source_row_no),
    CONSTRAINT ux_obs_context UNIQUE (id, route_reference_version_id),
    CONSTRAINT ck_obs_source_row_no_non_negative CHECK (source_row_no >= 0),
    CONSTRAINT ck_obs_phase CHECK (phase IN ('IN_TRANSIT', 'ARRIVING', 'DEPARTED')),
    CONSTRAINT ck_obs_trip_key_needs_vehicle CHECK (vehicle_id IS NOT NULL OR vehicle_trip_key IS NULL),
    CONSTRAINT ck_obs_seat_xor_reason CHECK ((remaining_seats IS NULL) <> (seat_unavailable_reason IS NULL)),
    CONSTRAINT ck_obs_remaining_seats_non_negative CHECK (remaining_seats IS NULL OR remaining_seats >= 0),
    CONSTRAINT ck_obs_seat_unavailable_reason CHECK (
        seat_unavailable_reason IS NULL OR seat_unavailable_reason IN ('MINUS_ONE', 'FIELD_ABSENT')
    ),
    CONSTRAINT ck_obs_crowded_code CHECK (crowded_code IS NULL OR crowded_code BETWEEN 1 AND 4)
);

CREATE UNIQUE INDEX ux_obs_vehicle_per_poll
    ON vehicle_observation (poll_id, vehicle_id)
    WHERE vehicle_id IS NOT NULL;

CREATE INDEX ix_obs_last_arrival
    ON vehicle_observation (route_reference_version_id, stop_order, observed_at DESC);

CREATE INDEX ix_obs_trip_progress
    ON vehicle_observation (route_reference_version_id, vehicle_trip_key, stop_order);
