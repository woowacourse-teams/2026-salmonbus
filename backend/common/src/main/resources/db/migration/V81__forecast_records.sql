SET LOCAL lock_timeout = '5s';

-- 예보 실행·예측 기록·통과·정답·미해결 예측을 담는 테이블이다.
-- 모두 배치 예약 시각의 한국 날짜(source_date)로 나누고 FK가 없다.
-- 날짜 파티션(<table>_pYYYYMMDD)은 worker의 유지 작업이 미리 만든다. 없는 날짜의 줄은 기본 파티션으로 들어간다.

CREATE TABLE forecast_run (
    source_date date NOT NULL,
    observation_batch_id bigint NOT NULL,
    route_id bigint NOT NULL,
    route_version_id bigint NOT NULL,
    received_at timestamptz NOT NULL,
    confirmed_at timestamptz NOT NULL,
    model_deployment_id bigint,
    outcome varchar(24) NOT NULL,
    prediction_count integer NOT NULL,
    -- 배치의 수집 사실이다. 채점이 다시 뜰 때 그날의 수집 구멍과 관측 줄 수를 관측 줄을 다시 세지 않고 되살리는 데 쓴다.
    -- source_incomplete는 저장 줄 수와 실제로 남은 줄 수가 다른 성공 배치, counted_rows는 하루 관측 상한에 더할 줄 수다.
    source_incomplete boolean,
    counted_rows integer,
    CONSTRAINT pk_forecast_run PRIMARY KEY (source_date, observation_batch_id),
    CONSTRAINT ck_forecast_run_outcome CHECK (outcome IN ('PUBLISHED','EMPTY','LATE','OUT_OF_SCOPE','INPUT_UNAVAILABLE')),
    CONSTRAINT ck_forecast_run_count CHECK (prediction_count >= 0),
    CONSTRAINT ck_forecast_run_collection CHECK ((source_incomplete IS NULL) = (counted_rows IS NULL) AND counted_rows >= 0)
) PARTITION BY RANGE (source_date);
CREATE INDEX ix_forecast_run_route ON forecast_run (route_version_id, received_at DESC, observation_batch_id DESC);
CREATE INDEX ix_forecast_run_replay ON forecast_run (route_id, received_at, observation_batch_id);
CREATE TABLE forecast_run_default PARTITION OF forecast_run DEFAULT;

CREATE TABLE forecast_record (
    source_date date NOT NULL,
    vehicle_observation_id bigint NOT NULL,
    observation_batch_id bigint NOT NULL,
    source_row_number integer NOT NULL,
    route_id bigint NOT NULL,
    route_version_id bigint NOT NULL,
    vehicle_id varchar(40) NOT NULL,
    passed_stop_order integer NOT NULL,
    trip_start_observation_id bigint NOT NULL,
    chain_start_observation_id bigint NOT NULL,
    down boolean NOT NULL,
    capacity integer NOT NULL,
    extended boolean NOT NULL,
    target_stop_orders integer[] NOT NULL,
    expected_seats double precision[] NOT NULL,
    full_probabilities double precision[] NOT NULL,
    CONSTRAINT pk_forecast_record PRIMARY KEY (source_date, vehicle_observation_id),
    CONSTRAINT ck_forecast_record_arrays CHECK (
        cardinality(target_stop_orders) > 0
        AND cardinality(expected_seats) = cardinality(target_stop_orders)
        AND cardinality(full_probabilities) = cardinality(target_stop_orders))
) PARTITION BY RANGE (source_date);
CREATE INDEX ix_forecast_record_batch ON forecast_record (observation_batch_id);
CREATE TABLE forecast_record_default PARTITION OF forecast_record DEFAULT;

CREATE TABLE stop_pass (
    source_date date NOT NULL,
    route_id bigint NOT NULL,
    trip_start_observation_id bigint NOT NULL,
    stop_order integer NOT NULL,
    route_version_id bigint NOT NULL,
    vehicle_id varchar(40) NOT NULL,
    chain_start_observation_id bigint NOT NULL,
    departure_date date NOT NULL,
    arrival_observation_id bigint NOT NULL,
    arrival_seats integer NOT NULL,
    arrival_confirmed_at timestamptz NOT NULL,
    arrival_received_at timestamptz NOT NULL,
    first_observation_id bigint NOT NULL,
    first_confirmed_at timestamptz NOT NULL,
    last_observation_id bigint NOT NULL,
    last_seats integer NOT NULL,
    last_confirmed_at timestamptz NOT NULL,
    last_received_at timestamptz NOT NULL,
    reached_known_seat_at timestamptz,
    closure_observation_id bigint NOT NULL,
    closure_batch_id bigint NOT NULL,
    closed_at timestamptz NOT NULL,
    CONSTRAINT pk_stop_pass PRIMARY KEY (source_date, route_id, trip_start_observation_id, stop_order)
) PARTITION BY RANGE (source_date);
CREATE INDEX ix_stop_pass_route_closed ON stop_pass (route_id, closed_at);
CREATE INDEX ix_stop_pass_chain ON stop_pass (route_id, chain_start_observation_id, stop_order);
CREATE TABLE stop_pass_default PARTITION OF stop_pass DEFAULT;

-- 정답은 모델 배포의 계수로 정해지므로 같은 통과·같은 정원이라도 모델 배포마다 한 줄이다.
CREATE TABLE pass_label (
    source_date date NOT NULL,
    route_id bigint NOT NULL,
    trip_start_observation_id bigint NOT NULL,
    stop_order integer NOT NULL,
    capacity integer NOT NULL,
    label_version varchar(80) NOT NULL,
    model_deployment_id bigint NOT NULL,
    state varchar(16) NOT NULL,
    reason varchar(64),
    label_value double precision,
    previous_last_observation_id bigint,
    previous_closure_observation_id bigint,
    parameter_hash char(64),
    computed_at timestamptz NOT NULL,
    CONSTRAINT pk_pass_label PRIMARY KEY (source_date, route_id, trip_start_observation_id, stop_order, capacity, label_version,
        model_deployment_id),
    CONSTRAINT ck_pass_label_state CHECK (state IN ('SETTLED','UNSUPPORTED')),
    CONSTRAINT ck_pass_label_value CHECK ((state = 'SETTLED') = (label_value IS NOT NULL)),
    CONSTRAINT ck_pass_label_reason CHECK ((state = 'SETTLED') = (reason IS NULL))
) PARTITION BY RANGE (source_date);
CREATE TABLE pass_label_default PARTITION OF pass_label DEFAULT;

CREATE TABLE forecast_unresolved (
    source_date date NOT NULL,
    vehicle_observation_id bigint NOT NULL,
    target_stop_order integer NOT NULL,
    route_id bigint NOT NULL,
    state varchar(16) NOT NULL,
    reason varchar(64) NOT NULL,
    closed_at timestamptz NOT NULL,
    CONSTRAINT pk_forecast_unresolved PRIMARY KEY (source_date, vehicle_observation_id, target_stop_order),
    CONSTRAINT ck_forecast_unresolved_state CHECK (state IN ('UNSUPPORTED','LOST'))
) PARTITION BY RANGE (source_date);
CREATE TABLE forecast_unresolved_default PARTITION OF forecast_unresolved DEFAULT;

-- S3 내보내기 장부다. 날짜 파티션 내보내기는 (테이블, 날짜)마다 segment 0 한 줄을 쓴다.
-- 날짜로 나뉘지 않은 테이블을 통째로 내보낼 때는 source_date 2000-01-01에 기본 키 순서의 조각마다 한 줄을 쓴다.
-- 조각 n은 조각 n-1의 key_to 다음부터 자기 key_to까지의 줄이고, key_from·key_to는 조각의 첫 줄·마지막 줄 기본 키를 담은
-- JSON 배열 텍스트다.
CREATE TABLE daily_export (
    table_name varchar(63) NOT NULL,
    source_date date NOT NULL,
    object_key text NOT NULL,
    row_count bigint NOT NULL,
    sha256 char(64) NOT NULL,
    exported_at timestamptz NOT NULL,
    verified_at timestamptz,
    dropped_at timestamptz,
    segment integer NOT NULL DEFAULT 0,
    key_from text,
    key_to text,
    CONSTRAINT pk_daily_export PRIMARY KEY (table_name, source_date, segment),
    CONSTRAINT ck_daily_export_segment CHECK (segment >= 0),
    CONSTRAINT ck_daily_export_key_range CHECK ((key_from IS NULL) = (key_to IS NULL))
);
