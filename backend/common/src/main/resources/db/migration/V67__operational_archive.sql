-- 구 Worker/복원 프로세스를 중지한 뒤 적용한다. 기존 S3 원본은 삭제하지 않는다.
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM observation_batch LIMIT 1)
        AND EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()
        AND application_name IN('salmonbus-worker','salmonbus-archive','salmonbus-historical-migration')) THEN
        RAISE EXCEPTION 'Worker와 기존 이관/복원 프로세스를 중지한 뒤 전환해야 한다';
    END IF;
    IF EXISTS(SELECT 1 FROM stop_demand_rebuild_request)
       OR EXISTS(SELECT 1 FROM stop_demand_rebuild_progress)
       OR EXISTS(SELECT 1 FROM stop_demand_rebuild_total)
       OR EXISTS(SELECT 1 FROM stop_demand_rebuild_scan) THEN
        RAISE EXCEPTION '진행 중인 수요 재구축을 인계한 뒤 전환해야 한다';
    END IF;
    IF EXISTS(SELECT 1 FROM evaluation_archive_batch
        WHERE storage_state='LIVE' AND abandoned_at IS NULL AND restored_at IS NULL) THEN
        RAISE EXCEPTION '기존 이관 예약을 종료한 뒤 전환해야 한다';
    END IF;
END $$;
-- 원본 FK를 동적으로 찾아 제거한다. 운영별 자동 생성 제약 이름 차이를 허용한다.
DO $$ DECLARE c record; BEGIN
    FOR c IN SELECT conname FROM pg_constraint
        WHERE conrelid='evaluation_archive_member'::regclass AND contype='f'
          AND confrelid IN ('seat_forecast'::regclass,'vehicle_observation'::regclass)
    LOOP EXECUTE format('ALTER TABLE evaluation_archive_member DROP CONSTRAINT %I',c.conname); END LOOP;
END $$;
ALTER TABLE evaluation_archive_batch RENAME TO archive_legacy_batch;
ALTER TABLE evaluation_archive_member RENAME TO archive_legacy_member;
DROP TABLE evaluation_archive_scan;
DROP TABLE stop_demand_rebuild_scan, stop_demand_rebuild_total,
    stop_demand_rebuild_progress, stop_demand_rebuild_request;

CREATE TABLE operational_archive_job (
    id uuid PRIMARY KEY,
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    source_date date NOT NULL,
    cutoff timestamptz NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('EXPORTING','VERIFIED','PURGING','DONE')),
    lease_token uuid NOT NULL,
    lease_until timestamptz NOT NULL,
    retry_at timestamptz NOT NULL DEFAULT '-infinity',
    last_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    manifest_sha256 varchar(64),
    failure_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    UNIQUE(route_version_id,source_date)
);
CREATE INDEX ix_archive_retry ON operational_archive_job(retry_at,last_attempt_at)
    WHERE state<>'DONE';
CREATE TABLE operational_archive_file (
    job_id uuid NOT NULL REFERENCES operational_archive_job(id),
    file_number integer NOT NULL,
    table_name varchar(80) NOT NULL,
    object_key text NOT NULL,
    sha256 varchar(64) NOT NULL,
    row_count integer NOT NULL,
    byte_count integer NOT NULL,
    purged boolean NOT NULL DEFAULT false,
    PRIMARY KEY(job_id,file_number)
);
CREATE TABLE operational_archive_boundary (
    route_version_id bigint PRIMARY KEY REFERENCES route_version(id),
    closed_before timestamptz NOT NULL
);
-- 새 노선만 명시적인 빈 기준으로 시작할 수 있다. 기존 미초기화 이력은 조용히 건너뛰지 않는다.
INSERT INTO stop_demand_baseline(route_version_id, initialized)
SELECT v.id,true FROM route_version v
WHERE NOT EXISTS(SELECT 1 FROM vehicle_observation o WHERE o.route_version_id=v.id)
ON CONFLICT(route_version_id) DO NOTHING;
CREATE TABLE vehicle_capacity_history (
    source_id varchar(40) NOT NULL,
    vehicle_id varchar(40) NOT NULL,
    evidence_at timestamptz NOT NULL,
    evidence_batch_id bigint NOT NULL DEFAULT 0,
    capacity integer NOT NULL CHECK(capacity BETWEEN 1 AND 70),
    PRIMARY KEY(source_id,vehicle_id,evidence_at,evidence_batch_id)
);
INSERT INTO vehicle_capacity_history
SELECT source_id,vehicle_id,evidence_at,COALESCE(evidence_batch_id,0),capacity FROM vehicle_capacity;
CREATE FUNCTION record_vehicle_capacity_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    INSERT INTO vehicle_capacity_history VALUES(NEW.source_id,NEW.vehicle_id,NEW.evidence_at,
        COALESCE(NEW.evidence_batch_id,0),NEW.capacity)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER vehicle_capacity_history_changed AFTER INSERT OR UPDATE ON vehicle_capacity
FOR EACH ROW EXECUTE FUNCTION record_vehicle_capacity_history();

CREATE FUNCTION initialize_new_route_statistics() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    INSERT INTO stop_demand_baseline(route_version_id,initialized) VALUES(NEW.id,true);
    RETURN NEW;
END $$;
CREATE TRIGGER new_route_statistics AFTER INSERT ON route_version
FOR EACH ROW EXECUTE FUNCTION initialize_new_route_statistics();
