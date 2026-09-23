-- SAL-134: 운영 앱을 중지한 뒤 전환 도구가 이 버전까지만 적용한다.
-- 기존 행을 복사하거나 기존 컬럼을 삭제하지 않는다. V18은 검증 완료를 확인한다.
CREATE TABLE sal134_transition (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    backup_id text NOT NULL CHECK (length(trim(backup_id)) > 0),
    source_snapshot jsonb NOT NULL DEFAULT '{}',
    prepared_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified_at timestamptz
);
CREATE TABLE sal134_transition_progress (
    step varchar(80) PRIMARY KEY,
    cursor jsonb NOT NULL,
    processed_rows bigint NOT NULL DEFAULT 0 CHECK (processed_rows >= 0),
    completed boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE observation_batch ADD COLUMN input_confirmed_at timestamptz;
-- 평가 근거로 사용한 배치도 확인할 수 있도록 전환 중에만 쓰는 인덱스다.
CREATE INDEX ix_sal134_forecast_arrival ON seat_forecast(arrival_observation_id);

CREATE TABLE forecast_publication (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_batch_id bigint NOT NULL UNIQUE,
    source_attempt_number integer NOT NULL CHECK (source_attempt_number > 0),
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    model_deployment_id bigint REFERENCES model_deployment(id),
    demand_statistics_revision integer,
    quality_revision bigint CHECK (quality_revision > 0),
    observed_at timestamptz NOT NULL,
    generated_at timestamptz,
    published_at timestamptz NOT NULL,
    prediction_count integer NOT NULL CHECK (prediction_count >= 0),
    provenance varchar(20) NOT NULL DEFAULT 'RECORDED',
    CONSTRAINT ux_publication_route UNIQUE (id, route_version_id),
    CONSTRAINT fk_publication_batch FOREIGN KEY(source_batch_id, route_version_id)
        REFERENCES observation_batch(id, route_version_id),
    CONSTRAINT ck_publication_provenance CHECK (
        (provenance = 'RECORDED' AND model_deployment_id IS NOT NULL
            AND demand_statistics_revision IS NOT NULL AND quality_revision IS NOT NULL
            AND generated_at IS NOT NULL)
        OR (provenance = 'LEGACY_UNKNOWN' AND prediction_count = 0
            AND model_deployment_id IS NULL AND demand_statistics_revision IS NULL
            AND quality_revision IS NULL AND generated_at IS NULL)
    )
);
CREATE INDEX ix_publication_latest ON forecast_publication(route_version_id, observed_at DESC, source_batch_id DESC);
ALTER TABLE seat_forecast ADD COLUMN publication_id bigint;
ALTER TABLE seat_forecast ADD CONSTRAINT fk_forecast_publication
    FOREIGN KEY (publication_id, route_version_id) REFERENCES forecast_publication(id, route_version_id);

CREATE TABLE forecast_evaluation (
    vehicle_observation_id bigint NOT NULL,
    target_stop_order integer NOT NULL,
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    scoring_state varchar(16) NOT NULL DEFAULT 'PENDING',
    arrival_observation_id bigint,
    seats_on_arrival integer,
    scored_at timestamptz,
    arrived_at timestamptz,
    arrival_route_version_id bigint,
    arrival_vehicle_id varchar(40),
    arrival_stop_order integer,
    arrival_running_state integer,
    arrival_remaining_seats integer,
    arrival_seat_unknown_reason varchar(20),
    arrival_vehicle_trip_key varchar(120),
    arrival_quality_direction bigint,
    CONSTRAINT pk_forecast_evaluation PRIMARY KEY(vehicle_observation_id, target_stop_order),
    CONSTRAINT fk_evaluation_source_context FOREIGN KEY(vehicle_observation_id, route_version_id)
        REFERENCES vehicle_observation(id, route_version_id),
    CONSTRAINT fk_evaluation_forecast FOREIGN KEY(vehicle_observation_id, target_stop_order)
        REFERENCES seat_forecast(vehicle_observation_id, target_stop_order),
    CONSTRAINT ck_evaluation_state CHECK (scoring_state IN ('PENDING','SETTLED','SKIPPED','LOST','SEAT_MISSING')),
    CONSTRAINT ck_evaluation_arrival CHECK ((arrival_observation_id IS NOT NULL) = (scoring_state IN ('SETTLED','SEAT_MISSING'))),
    CONSTRAINT ck_evaluation_seats CHECK ((seats_on_arrival IS NOT NULL) = (scoring_state = 'SETTLED')),
    CONSTRAINT ck_evaluation_seats_nonnegative CHECK (seats_on_arrival >= 0),
    CONSTRAINT ck_evaluation_scored_at CHECK ((scored_at IS NULL) = (scoring_state = 'PENDING')),
    CONSTRAINT ck_evaluation_evidence CHECK (
        (arrival_observation_id IS NULL AND arrived_at IS NULL AND arrival_route_version_id IS NULL
            AND arrival_stop_order IS NULL AND arrival_running_state IS NULL
            AND arrival_remaining_seats IS NULL AND arrival_seat_unknown_reason IS NULL
            AND arrival_vehicle_id IS NULL AND arrival_vehicle_trip_key IS NULL AND arrival_quality_direction IS NULL)
        OR (arrival_observation_id IS NOT NULL AND arrived_at IS NOT NULL
            AND arrival_route_version_id IS NOT NULL AND arrival_stop_order IS NOT NULL
            AND arrival_running_state IS NOT NULL AND arrival_quality_direction IS NOT NULL
            AND ((arrival_remaining_seats IS NULL) <> (arrival_seat_unknown_reason IS NULL)))
    )
);
CREATE INDEX ix_evaluation_pending ON forecast_evaluation(route_version_id, vehicle_observation_id) WHERE scoring_state = 'PENDING';
CREATE INDEX ix_evaluation_settled_arrival ON forecast_evaluation(arrived_at, vehicle_observation_id) WHERE scoring_state = 'SETTLED';

CREATE TABLE demand_statistics_version (
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    calculation_version varchar(40) NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    data_until timestamptz NOT NULL,
    computed_at timestamptz NOT NULL,
    quality_revision bigint NOT NULL CHECK (quality_revision > 0),
    cell_count integer NOT NULL CHECK (cell_count >= 0),
    input_checkpoint bigint,
    PRIMARY KEY(route_version_id, calculation_version, revision)
);
CREATE TABLE route_data_quality (
    route_id bigint PRIMARY KEY REFERENCES route(id),
    quality_revision bigint NOT NULL DEFAULT 1 CHECK (quality_revision > 0)
);
CREATE TABLE route_version_quality_policy (
    route_version_id bigint PRIMARY KEY REFERENCES route_version(id),
    maximum_observation_gap_seconds integer CHECK (maximum_observation_gap_seconds > 0),
    observation_gap_evidence text,
    CONSTRAINT ck_quality_policy_evidence CHECK (maximum_observation_gap_seconds IS NULL
        OR (observation_gap_evidence IS NOT NULL AND length(trim(observation_gap_evidence)) > 0))
);
CREATE TABLE observation_trip_assignment (
    observation_id bigint PRIMARY KEY REFERENCES vehicle_observation(id) ON DELETE CASCADE,
    trip_id varchar(120) NOT NULL REFERENCES vehicle_one_way_trip(id)
);
CREATE INDEX ix_trip_assignment_trip ON observation_trip_assignment(trip_id);

CREATE TABLE model_active_slot (
    id smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    model_deployment_id bigint REFERENCES model_deployment(id),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
INSERT INTO model_active_slot(id) VALUES(1);
CREATE TABLE model_activation_request (
    request_id uuid PRIMARY KEY,
    expected_active_version bigint NOT NULL CHECK(expected_active_version >= 0),
    target_deployment_id bigint NOT NULL REFERENCES model_deployment(id),
    resulting_active_version bigint NOT NULL CHECK(resulting_active_version >= expected_active_version),
    activated_at timestamptz NOT NULL
);

-- 이관 장부를 참조하지 않는 영구 학습 제외 기록. 기존 장부는 감사 자료로 보존한다.
CREATE TABLE model_training_exclusion (
    release_id varchar(80) NOT NULL,
    bundle_digest varchar(64) NOT NULL CHECK (bundle_digest ~ '^[0-9a-f]{64}$'),
    observed_model_deployment_id bigint,
    calculation_version varchar(40) NOT NULL UNIQUE,
    observed_activated_at timestamptz,
    final_cutover_at timestamptz,
    statistics_baseline_count bigint NOT NULL CHECK(statistics_baseline_count >= 0),
    statistics_baseline_not_after timestamptz NOT NULL,
    classification varchar(24) NOT NULL CHECK(classification IN ('TEMPORARY_RELEASE','INVALID_RELEASE','OPERATOR_EXCLUDED')),
    decision_reference varchar(120) NOT NULL,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY(release_id,bundle_digest),
    CHECK(final_cutover_at IS NULL OR final_cutover_at > observed_activated_at)
);
CREATE TABLE statistics_training_exclusion (
    freeze_id uuid NOT NULL,
    release_id varchar(80) NOT NULL,
    bundle_digest varchar(64) NOT NULL,
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    calculation_version varchar(40) NOT NULL,
    revision integer NOT NULL CHECK(revision >= 1),
    data_until timestamptz NOT NULL,
    computed_at timestamptz NOT NULL,
    frozen_cell_count integer NOT NULL CHECK(frozen_cell_count >= 1),
    frozen_at timestamptz NOT NULL,
    PRIMARY KEY(freeze_id,route_version_id,calculation_version,revision,data_until,computed_at)
);

-- 검증 이후 어느 쓰기라도 발생하면 최종 전환 승인을 무효화한다.
CREATE FUNCTION invalidate_sal134_verification() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
    UPDATE sal134_transition SET verified_at = NULL WHERE verified_at IS NOT NULL;
    RETURN NULL;
END;
$body$;
DO $body$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'daily_call_quota','route','route_version','route_stop','observation_batch','vehicle_observation',
        'seat_forecast','stop_demand_statistics','same_day_full_outcomes','model_deployment',
        'vehicle_one_way_trip','trip_quality_rebuild','forecast_publication','forecast_evaluation',
        'demand_statistics_version','route_data_quality','route_version_quality_policy','observation_trip_assignment',
        'model_active_slot','model_activation_request','model_training_exclusion','statistics_training_exclusion',
        'training_model_release_exclusion','training_statistics_generation_exclusion',
        'historical_import_batch','historical_import_dataset_seal','historical_import_route_boundary',
        'historical_import_route_binding','historical_import_shard','historical_import_record','migration_source_record',
        'forecast_cutover_control','temporary_statistics_generation_freeze','sal134_transition_progress',
        'stop_demand_seed_import','stop_demand_seed_hourly_total','stop_demand_seed_generation'
    ] LOOP
        IF to_regclass(format('%I.%I', current_schema(), table_name)) IS NOT NULL THEN
            EXECUTE format('CREATE TRIGGER invalidate_sal134_verification AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION invalidate_sal134_verification()', table_name);
        END IF;
    END LOOP;
END;
$body$;
