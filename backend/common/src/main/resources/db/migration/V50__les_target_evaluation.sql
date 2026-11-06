-- Les 목표는 기존 도착 관측 정산/수요 통계와 분리한다. 기존 숫자와 판정은 변경하지 않는다.
SET LOCAL lock_timeout = '5s';
ALTER TABLE seat_forecast
    ADD COLUMN seat_target varchar(40) NOT NULL DEFAULT 'FIRST_POST_STOP_OBSERVATION',
    ADD COLUMN probability_target varchar(40) NOT NULL DEFAULT 'FIRST_POST_STOP_ZERO',
    ADD COLUMN label_version varchar(80),
    ADD COLUMN feature_snapshot jsonb,
    ADD COLUMN inference_bundle_hash varchar(64);
ALTER TABLE seat_forecast ADD CONSTRAINT ck_les_forecast_contract CHECK (
    seat_target <> 'AFTER_ALIGHTING_ESTIMATE' OR (
        probability_target='DEPARTURE_ZERO' AND label_version IS NOT NULL
        AND feature_snapshot IS NOT NULL AND inference_bundle_hash IS NOT NULL AND inference_bundle_hash ~ '^[0-9a-f]{64}$'));

CREATE TABLE les_forecast_evaluation_work (
    vehicle_observation_id bigint NOT NULL,
    target_stop_order integer NOT NULL,
    target_kind varchar(40) NOT NULL CHECK (target_kind IN ('AFTER_ALIGHTING_ESTIMATE','DEPARTURE_ZERO')),
    label_version varchar(80) NOT NULL,
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason varchar(80) NOT NULL DEFAULT 'AWAITING_OBSERVATION',
    PRIMARY KEY(vehicle_observation_id,target_stop_order,target_kind,label_version),
    FOREIGN KEY(vehicle_observation_id,route_version_id)
        REFERENCES vehicle_observation(id,route_version_id),
    FOREIGN KEY(vehicle_observation_id,target_stop_order)
        REFERENCES seat_forecast(vehicle_observation_id,target_stop_order)
);
CREATE INDEX ix_les_evaluation_due ON les_forecast_evaluation_work(next_attempt_at,vehicle_observation_id);
CREATE TABLE les_forecast_evaluation_result (
    vehicle_observation_id bigint NOT NULL,
    target_stop_order integer NOT NULL,
    target_kind varchar(40) NOT NULL,
    label_version varchar(80) NOT NULL,
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    scoring_state varchar(24) NOT NULL CHECK(scoring_state IN ('SETTLED','UNSUPPORTED','LOST')),
    label_value double precision,
    reason varchar(80),
    pre_observation_id bigint REFERENCES vehicle_observation(id),
    post_observation_id bigint REFERENCES vehicle_observation(id),
    closure_observation_id bigint REFERENCES vehicle_observation(id),
    previous_post_observation_id bigint REFERENCES vehicle_observation(id),
    previous_closure_observation_id bigint REFERENCES vehicle_observation(id),
    capacity_observation_id bigint REFERENCES vehicle_observation(id),
    trip_key varchar(120),
    quality_direction bigint,
    quality_revision bigint NOT NULL CHECK(quality_revision>0),
    event_at timestamptz,
    known_at timestamptz,
    scored_at timestamptz NOT NULL,
    parameter_hash varchar(64),
    capacity integer CHECK(capacity BETWEEN 0 AND 70),
    capacity_policy varchar(80),
    evidence_archive_hash varchar(64),
    PRIMARY KEY(vehicle_observation_id,target_stop_order,target_kind,label_version,quality_revision),
    FOREIGN KEY(vehicle_observation_id,route_version_id)
        REFERENCES vehicle_observation(id,route_version_id),
    FOREIGN KEY(vehicle_observation_id,target_stop_order)
        REFERENCES seat_forecast(vehicle_observation_id,target_stop_order),
    CHECK ((scoring_state='SETTLED' AND label_value IS NOT NULL AND label_value BETWEEN 0 AND 70)
        OR (scoring_state<>'SETTLED' AND label_value IS NULL AND reason IS NOT NULL)),
    CHECK (target_kind IN ('AFTER_ALIGHTING_ESTIMATE','DEPARTURE_ZERO')),
    CHECK (target_kind<>'DEPARTURE_ZERO' OR label_value IS NULL OR label_value IN (0,1))
);
-- 모든 예보를 저장한다. 기존 상세 표본화/정수 compact/S3 v1에 넣지 않는다.
-- 원자료 FK는 미지원 아카이브 형식으로 근거가 지워지는 것을 차단한다.
CREATE FUNCTION register_les_evaluation_work() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.seat_target='AFTER_ALIGHTING_ESTIMATE' THEN
        INSERT INTO les_forecast_evaluation_work(vehicle_observation_id,target_stop_order,target_kind,
            label_version,route_version_id)
        SELECT NEW.vehicle_observation_id,NEW.target_stop_order,kind,NEW.label_version,NEW.route_version_id
        FROM (VALUES ('AFTER_ALIGHTING_ESTIMATE'),('DEPARTURE_ZERO')) AS targets(kind)
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_les_evaluation_work AFTER INSERT ON seat_forecast
    FOR EACH ROW EXECUTE FUNCTION register_les_evaluation_work();

CREATE OR REPLACE VIEW forecast_evaluation_pending AS
SELECT forecast.vehicle_observation_id,forecast.target_stop_order,work.route_version_id
FROM forecast_evaluation_work work
JOIN seat_forecast forecast ON forecast.vehicle_observation_id=work.vehicle_observation_id
    AND forecast.route_version_id=work.route_version_id
WHERE forecast.seat_target<>'AFTER_ALIGHTING_ESTIMATE'
AND NOT EXISTS(SELECT 1 FROM forecast_evaluation_result r WHERE r.vehicle_observation_id=forecast.vehicle_observation_id
    AND r.target_stop_order=forecast.target_stop_order)
AND NOT EXISTS(SELECT 1 FROM forecast_evaluation_compact r WHERE r.vehicle_observation_id=forecast.vehicle_observation_id
    AND r.target_stop_order=forecast.target_stop_order)
AND NOT EXISTS(SELECT 1 FROM evaluation_archive_member r WHERE r.vehicle_observation_id=forecast.vehicle_observation_id
    AND r.target_stop_order=forecast.target_stop_order);

-- 품질 재조사/판본 변경 결과를 현재 성적에 섞지 않는다. 원본 결과는 보존한다.
CREATE VIEW les_quality_eligible_evaluation AS
SELECT result.* FROM les_forecast_evaluation_result result
JOIN route_version v ON v.id=result.route_version_id
JOIN route_data_quality q ON q.route_id=v.route_id AND q.quality_revision=result.quality_revision
JOIN forecast_eligible_observation source ON source.id=result.vehicle_observation_id
WHERE NOT EXISTS(SELECT 1 FROM trip_quality_rebuild rebuild
    WHERE rebuild.route_version_id=result.route_version_id AND NOT rebuild.completed
      AND (rebuild.vehicle_id='' OR rebuild.vehicle_id=source.vehicle_id));

CREATE TABLE les_evaluation_archive (
    vehicle_observation_id bigint NOT NULL,
    target_stop_order integer NOT NULL,
    target_kind varchar(40) NOT NULL,
    label_version varchar(80) NOT NULL,
    quality_revision bigint NOT NULL CHECK(quality_revision>0),
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    label_value double precision,
    scoring_state varchar(24) NOT NULL,
    reason varchar(80),
    event_at timestamptz,
    scored_at timestamptz NOT NULL,
    object_hash varchar(64) NOT NULL CHECK(object_hash ~ '^[0-9a-f]{64}$'),
    storage_state varchar(16) NOT NULL CHECK(storage_state IN ('LIVE','PURGED')),
    verified_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    restored_at timestamptz,
    PRIMARY KEY(vehicle_observation_id,target_stop_order,target_kind,label_version,quality_revision),
    FOREIGN KEY(vehicle_observation_id,route_version_id)
        REFERENCES vehicle_observation(id,route_version_id),
    FOREIGN KEY(vehicle_observation_id,target_stop_order)
        REFERENCES seat_forecast(vehicle_observation_id,target_stop_order)
);
-- 이관 파일에 원자료 snapshot이 들어 있다. 복원 시 원자료가 없어도
-- evidence_archive_hash로 해당 파일을 참조하며 원자료 FK를 다시 만들지 않는다.

CREATE VIEW les_forecast_evaluation_metric AS
SELECT vehicle_observation_id,target_stop_order,target_kind,label_version,quality_revision,
       route_version_id,label_value,scoring_state,event_at,scored_at,reason
FROM les_forecast_evaluation_result
UNION ALL
SELECT vehicle_observation_id,target_stop_order,target_kind,label_version,quality_revision,
       route_version_id,label_value,scoring_state,event_at,scored_at,reason
FROM les_evaluation_archive WHERE storage_state='PURGED';
CREATE VIEW les_quality_eligible_metric AS
SELECT result.* FROM les_forecast_evaluation_metric result
JOIN route_version v ON v.id=result.route_version_id
JOIN route_data_quality q ON q.route_id=v.route_id AND q.quality_revision=result.quality_revision
JOIN forecast_eligible_observation source ON source.id=result.vehicle_observation_id
WHERE NOT EXISTS(SELECT 1 FROM trip_quality_rebuild rebuild
    WHERE rebuild.route_version_id=result.route_version_id AND NOT rebuild.completed
      AND (rebuild.vehicle_id='' OR rebuild.vehicle_id=source.vehicle_id));

-- 부분 삭제, 입력 상한, 품질 조사 대기와 실제 관측 연결 실패를 구분한다.
-- 보관 이후에도 종료 이유가 사라지지 않는다.
CREATE VIEW les_evaluation_status AS
SELECT work.route_version_id,work.vehicle_observation_id,work.target_stop_order,work.target_kind,
       work.label_version,NULL::bigint AS quality_revision,'PENDING'::varchar(24) AS scoring_state,
       work.reason,work.next_attempt_at,NULL::timestamptz AS scored_at,
       'WORK_PENDING'::text AS retention_state
FROM les_forecast_evaluation_work work
UNION ALL
SELECT metric.route_version_id,metric.vehicle_observation_id,metric.target_stop_order,metric.target_kind,metric.label_version,
       metric.quality_revision,metric.scoring_state,metric.reason,NULL::timestamptz,metric.scored_at,
       CASE WHEN archive.storage_state='PURGED' THEN 'ARCHIVED'
            WHEN archive.restored_at IS NOT NULL THEN 'RESTORED'
            WHEN archive.storage_state='LIVE' THEN 'VERIFIED_LIVE'
            ELSE 'LIVE_UNARCHIVED' END
FROM les_forecast_evaluation_metric metric
LEFT JOIN les_evaluation_archive archive USING(vehicle_observation_id,target_stop_order,target_kind,label_version,quality_revision);
