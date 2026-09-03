ALTER TABLE forecast_cutover_control
    ADD COLUMN observation_batch_high_water bigint;

ALTER TABLE forecast_cutover_control
    ADD CONSTRAINT ck_forecast_cutover_high_water CHECK (
        (writes_paused AND observation_batch_high_water IS NOT NULL
            AND observation_batch_high_water >= 0)
        OR
        (NOT writes_paused AND observation_batch_high_water IS NULL));

ALTER TABLE temporary_statistics_generation_freeze
    ADD COLUMN observation_batch_high_water bigint;

ALTER TABLE temporary_statistics_generation_freeze
    ADD COLUMN cleaned_at timestamptz;

ALTER TABLE temporary_statistics_generation_freeze
    DROP CONSTRAINT ck_temporary_statistics_generation_status;

ALTER TABLE temporary_statistics_generation_freeze
    ADD CONSTRAINT ck_temporary_statistics_generation_status
        CHECK (
            (status = 'FROZEN' AND cleaned_at IS NULL)
            OR (status = 'CLEANED' AND cleaned_at IS NOT NULL)
            OR status = 'ABORTED');

CREATE TABLE stop_demand_seed_import (
    id                              uuid         NOT NULL,
    plan_sha256                     varchar(64)  NOT NULL,
    source_seed_sha256              varchar(64)  NOT NULL,
    source_receipt_sha256           varchar(64)  NOT NULL,
    source_canonical_sha256         varchar(64)  NOT NULL,
    delta_sha256                    varchar(64)  NOT NULL,
    combined_sha256                 varchar(64)  NOT NULL,
    calculation_version             varchar(40)  NOT NULL,
    final_cutover_at                timestamptz  NOT NULL,
    observation_batch_high_water    bigint       NOT NULL,
    generated_guard_seconds         integer      NOT NULL,
    settlement_guard_seconds        integer      NOT NULL,
    source_hourly_rows              integer      NOT NULL,
    source_samples                  bigint       NOT NULL,
    delta_hourly_rows               integer      NOT NULL,
    delta_samples                   bigint       NOT NULL,
    combined_hourly_rows            integer      NOT NULL,
    combined_samples                bigint       NOT NULL,
    computed_at                     timestamptz  NOT NULL,
    status                          varchar(16)  NOT NULL,
    applied_at                      timestamptz,
    rolled_back_at                  timestamptz,
    formal_deployment_id            bigint,
    formal_activated_at             timestamptz,
    unpaused_at                     timestamptz,
    reconciliation_receipt          jsonb        NOT NULL,
    CONSTRAINT pk_stop_demand_seed_import PRIMARY KEY (id),
    CONSTRAINT ux_stop_demand_seed_import_plan UNIQUE (plan_sha256),
    CONSTRAINT fk_stop_demand_seed_import_formal_deployment FOREIGN KEY (formal_deployment_id)
        REFERENCES model_deployment (id),
    CONSTRAINT ck_stop_demand_seed_import_digests CHECK (
        plan_sha256 ~ '^[0-9a-f]{64}$'
        AND source_seed_sha256 ~ '^[0-9a-f]{64}$'
        AND source_receipt_sha256 ~ '^[0-9a-f]{64}$'
        AND source_canonical_sha256 ~ '^[0-9a-f]{64}$'
        AND delta_sha256 ~ '^[0-9a-f]{64}$'
        AND combined_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_stop_demand_seed_import_counts CHECK (
        observation_batch_high_water >= 0
        AND generated_guard_seconds >= 0
        AND settlement_guard_seconds >= 0
        AND source_hourly_rows >= 0
        AND source_samples >= 0
        AND delta_hourly_rows >= 0
        AND delta_samples >= 0
        AND combined_hourly_rows >= 0
        AND combined_samples >= 0),
    CONSTRAINT ck_stop_demand_seed_import_status CHECK (
        (status = 'APPLIED' AND applied_at IS NOT NULL AND rolled_back_at IS NULL)
        OR
        (status = 'ROLLED_BACK' AND applied_at IS NOT NULL AND rolled_back_at IS NOT NULL)),
    CONSTRAINT ck_stop_demand_seed_import_activation CHECK (
        (formal_deployment_id IS NULL AND formal_activated_at IS NULL AND unpaused_at IS NULL)
        OR
        (formal_deployment_id IS NOT NULL AND formal_activated_at IS NOT NULL AND unpaused_at IS NOT NULL
            AND formal_activated_at >= applied_at AND unpaused_at >= formal_activated_at))
);

CREATE UNIQUE INDEX ux_stop_demand_seed_import_active
    ON stop_demand_seed_import (calculation_version)
    WHERE status = 'APPLIED';

CREATE TABLE stop_demand_seed_hourly_total (
    seed_import_id                  uuid             NOT NULL,
    route_version_id                bigint           NOT NULL,
    stop_order                      integer          NOT NULL,
    arrival_date_kst                date             NOT NULL,
    arrival_hour_start              timestamptz      NOT NULL,
    fill_rate_total                 numeric          NOT NULL,
    net_boarding_total              numeric          NOT NULL,
    capacity_total                  numeric          NOT NULL,
    sample_count                    integer          NOT NULL,
    CONSTRAINT pk_stop_demand_seed_hourly_total PRIMARY KEY (
        seed_import_id, route_version_id, stop_order, arrival_hour_start),
    CONSTRAINT fk_stop_demand_seed_hourly_import FOREIGN KEY (seed_import_id)
        REFERENCES stop_demand_seed_import (id),
    CONSTRAINT fk_stop_demand_seed_hourly_stop FOREIGN KEY (route_version_id, stop_order)
        REFERENCES route_stop (route_version_id, stop_order),
    CONSTRAINT ck_stop_demand_seed_hourly_hour CHECK (
        arrival_hour_start = date_trunc('hour', arrival_hour_start)),
    CONSTRAINT ck_stop_demand_seed_hourly_values CHECK (
        sample_count > 0
        AND capacity_total > 0
        AND fill_rate_total BETWEEN 0 AND sample_count
        AND net_boarding_total BETWEEN -capacity_total AND capacity_total)
);

CREATE TABLE stop_demand_seed_generation (
    seed_import_id                  uuid         NOT NULL,
    route_version_id                bigint       NOT NULL,
    calculation_version             varchar(40)  NOT NULL,
    revision                        integer      NOT NULL,
    data_until                      timestamptz  NOT NULL,
    computed_at                     timestamptz  NOT NULL,
    cell_count                      integer      NOT NULL,
    cell_sha256                     varchar(64)  NOT NULL,
    CONSTRAINT pk_stop_demand_seed_generation PRIMARY KEY (seed_import_id, route_version_id),
    CONSTRAINT fk_stop_demand_seed_generation_import FOREIGN KEY (seed_import_id)
        REFERENCES stop_demand_seed_import (id),
    CONSTRAINT fk_stop_demand_seed_generation_route FOREIGN KEY (route_version_id)
        REFERENCES route_version (id),
    CONSTRAINT ck_stop_demand_seed_generation_values CHECK (
        revision >= 1 AND cell_count >= 1 AND cell_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE VIEW active_stop_demand_seed_hourly_total AS
SELECT hourly.route_version_id,
       hourly.stop_order,
       hourly.arrival_date_kst,
       hourly.arrival_hour_start,
       hourly.fill_rate_total,
       hourly.net_boarding_total,
       hourly.capacity_total,
       hourly.sample_count,
       seed.calculation_version,
       seed.final_cutover_at
FROM stop_demand_seed_hourly_total hourly
JOIN stop_demand_seed_import seed ON seed.id = hourly.seed_import_id
WHERE seed.status = 'APPLIED';
