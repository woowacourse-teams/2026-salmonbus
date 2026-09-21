-- V14(SAL-132)의 당일 집계에도 같은 품질 판정 버전을 적용한다.
-- 원본 좌석/위치/시각과 예측값은 덮어쓰지 않는다. 기존 행은 판정 전까지 사용 보류한다.
-- 작은 설정/버전은 기존 노선 행에 둔다. 관측마다 연결 행을 추가하지 않는다.
ALTER TABLE route ADD COLUMN quality_revision bigint NOT NULL DEFAULT 1 CHECK (quality_revision > 0);
ALTER TABLE route_version
    ADD COLUMN maximum_observation_gap_seconds integer CHECK (maximum_observation_gap_seconds > 0),
    ADD COLUMN observation_gap_evidence text,
    ADD CONSTRAINT ck_observation_gap_evidence CHECK (
        maximum_observation_gap_seconds IS NULL
        OR (observation_gap_evidence IS NOT NULL AND length(trim(observation_gap_evidence)) > 0));
CREATE TABLE vehicle_one_way_trip (
    id varchar(120) PRIMARY KEY,
    start_observation_id bigint NOT NULL REFERENCES vehicle_observation(id),
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    vehicle_id varchar(40),
    status varchar(32) NOT NULL CHECK (status IN ('ELIGIBLE', 'BOUNDARY_UNCONFIRMED', 'EXCLUDED')),
    boundary varchar(24) NOT NULL,
    rule_version varchar(60) NOT NULL,
    evidence_observation_id bigint REFERENCES vehicle_observation(id),
    assessed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 차량의 판정된 편도가 없으면 큰 관측 이력 탐색을 생략한다. 편도 생성 때만 인덱스에 추가한다.
CREATE INDEX ix_one_way_trip_vehicle ON vehicle_one_way_trip(route_version_id, vehicle_id);
-- vehicle_trip_key는 기존의 nullable 파생 열이다. 원본 좌석/위치/시각은 변경하지 않는다.
ALTER TABLE seat_forecast ADD COLUMN quality_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE stop_demand_statistics ADD COLUMN quality_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE same_day_full_outcomes ADD COLUMN quality_revision bigint NOT NULL DEFAULT 0;

-- 과거 자료를 정리하는 동안 해당 판본의 계산 입력을 잠근다. 커서는 batch 단위로 커밋한다.
CREATE TABLE trip_quality_rebuild (
    route_version_id bigint PRIMARY KEY REFERENCES route_version(id),
    last_batch_at timestamptz,
    last_batch_id bigint NOT NULL DEFAULT 0,
    until_at timestamptz NOT NULL,
    maximum_gap_seconds integer,
    completed boolean NOT NULL DEFAULT false,
    started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 판정 행이 없어도 사용을 보류한다. 현재 좌석 API는 이 view 대신 원본을 읽는다.
CREATE VIEW forecast_eligible_observation AS
SELECT observation.*, trip.start_observation_id AS quality_trip_id
FROM vehicle_observation observation
JOIN vehicle_one_way_trip trip ON trip.id = observation.vehicle_trip_key
WHERE trip.status = 'ELIGIBLE'
  AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild rebuild
                  WHERE rebuild.route_version_id = observation.route_version_id AND NOT rebuild.completed);

-- 표시는 해당 차량의 판정을 따른다. 보정 재사용은 의존 입력의 유효성도 확인한다.
CREATE VIEW quality_eligible_seat_forecast AS
SELECT forecast.*
FROM seat_forecast forecast
JOIN forecast_eligible_observation source ON source.id = forecast.vehicle_observation_id
LEFT JOIN forecast_eligible_observation arrival ON arrival.id = forecast.arrival_observation_id
WHERE forecast.arrival_observation_id IS NULL OR arrival.quality_trip_id = source.quality_trip_id;

CREATE VIEW quality_calibration_seat_forecast AS
SELECT forecast.* FROM quality_eligible_seat_forecast forecast
JOIN route_version version ON version.id = forecast.route_version_id
JOIN route quality ON quality.id = version.route_id
WHERE forecast.quality_revision = quality.quality_revision;

-- 원본이 적격이어도 이전 통계/입력으로 만든 예측은 학습 후보에 넣지 않는다.
CREATE VIEW quality_training_seat_forecast AS
SELECT * FROM quality_calibration_seat_forecast WHERE scoring_state = 'SETTLED';

-- 기존 historical schema가 있을 때도, 나중에 설치될 때도 같은 정의를 적용한다.
CREATE FUNCTION refresh_trip_quality_training_views() RETURNS void LANGUAGE plpgsql AS $body$
BEGIN
    IF to_regclass('public.training_model_release_exclusion') IS NOT NULL THEN
        EXECUTE $forecast$
CREATE OR REPLACE VIEW training_eligible_seat_forecast AS
SELECT forecast.*
FROM quality_training_seat_forecast forecast
JOIN model_deployment deployment
  ON deployment.id = forecast.model_deployment_id
WHERE NOT EXISTS (
    SELECT 1
    FROM training_model_release_exclusion excluded
    WHERE excluded.observed_model_deployment_id = deployment.id
      AND excluded.release_id = deployment.release_id
      AND excluded.bundle_digest = deployment.bundle_digest
      AND excluded.calculation_version = deployment.calculation_version
      AND excluded.observed_activated_at = deployment.activated_at
);
$forecast$;
        EXECUTE $statistics$
CREATE OR REPLACE VIEW training_eligible_stop_demand_statistics AS
SELECT statistics.*
FROM stop_demand_statistics statistics
JOIN route_version version ON version.id = statistics.route_version_id
JOIN route quality ON quality.id = version.route_id
WHERE statistics.quality_revision = quality.quality_revision
AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild r WHERE r.route_version_id = statistics.route_version_id AND NOT r.completed)
AND NOT EXISTS (
    SELECT 1
    FROM training_statistics_generation_exclusion excluded_generation
    WHERE excluded_generation.route_version_id = statistics.route_version_id
      AND excluded_generation.calculation_version = statistics.calculation_version
      AND excluded_generation.revision = statistics.revision
      AND excluded_generation.data_until = statistics.data_until
      AND excluded_generation.computed_at = statistics.computed_at
)
AND NOT EXISTS (
    -- current worker의 임시 모델 경유 기본 집계. final_cutover_at이 아직 없으면 activation 이후를
    -- 전부 fail-closed로 제외한다. freeze가 끝난 뒤에는 cutover 미만 exact window만 제외한다.
    SELECT 1
    FROM training_model_release_exclusion temporary
    WHERE temporary.classification = 'TEMPORARY_RELEASE'
      AND temporary.final_cutover_at IS NULL
      AND statistics.calculation_version IN (
          temporary.calculation_version, 'observed-max-capacity-v1')
      AND statistics.computed_at >= temporary.observed_activated_at
);
$statistics$;
    END IF;
END;
$body$;
SELECT refresh_trip_quality_training_views();
