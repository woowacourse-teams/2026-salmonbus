-- SAL-434 sal434-02가 지운 옛 정산 트리거 9개와 함수 7개를 마이그레이션의 정의 그대로 다시 만든다. 자동 실행하지 않는다.
--
-- 언제: sal434-02 실행 뒤 옛 정산(FORECAST_SCORING을 v2 밖으로 되돌림)으로 돌아가야 할 때. 승인을 받고, 옛 정산을 켜기 전에 실행한다.
--       sal434-03(옛 테이블 삭제) 뒤에는 쓸 수 없다. 트리거가 쓰는 테이블이 없으면 아무것도 바꾸지 않고 멈춘다.
-- 정의의 출처: register_alighting_evaluation_work는 V69__rename_alighting_model.sql:13-22,
--   트리거 trg_alighting_evaluation_work는 V55__*.sql:77-78(V69:23에서 이름을 바꿈),
--   나머지 함수 6개와 트리거 8개는 V80__incremental_alighting_settlement.sql:101-136, 138-273.
--   본문을 고치지 않는다. 고치면 ForecastPipelineTransitionSqlTest의 정의 비교가 실패한다.
-- 실행 전 조건: 옛 정산 대기·정산 테이블과 seat_forecast가 남아 있다. 실행 계정이 테이블 소유자다.
-- 트리거가 없던 동안의 일: 옛 정산 대기 줄이 생기지 않았고 관측 변경이 옛 정산 근거를 무효로 만들지 않았다.
--   그래서 마지막에 활성 근거를 모두 dirty로 표시해 옛 정산이 처음부터 다시 만들게 한다. 그 동안 발행된 예측의 옛 정산
--   대기 줄은 다시 만들지 않는다(옛 경로로 돌아간 뒤 새로 발행하는 예측부터 정산된다).
-- 잡는 잠금과 예상 시간: CREATE TRIGGER가 다섯 테이블에 SHARE ROW EXCLUSIVE를 잡는다. 카탈로그만 바꾼다. lock_timeout 5초.
-- 여러 번 실행해도 된다. 마이그레이션의 CREATE FUNCTION·CREATE TRIGGER를 CREATE OR REPLACE로만 바꿨다.
-- 실행: psql -X -v ON_ERROR_STOP=1 -f sal434-02-rollback-recreate-legacy-triggers.sql
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SET LOCAL search_path = public;

DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(name, ', ') INTO missing
      FROM unnest(ARRAY['seat_forecast', 'alighting_forecast_evaluation_work', 'alighting_settlement_batch_work',
          'alighting_settlement_generation', 'alighting_settlement_projection', 'observation_batch',
          'vehicle_observation', 'route_version']) AS name
     WHERE to_regclass('public.' || name) IS NULL;
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 트리거가 쓰는 테이블이 없다(sal434-03 이후에는 되돌릴 수 없다): %', missing;
    END IF;
END $$;

-- V69__rename_alighting_model.sql:13-22
CREATE OR REPLACE FUNCTION register_alighting_evaluation_work() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.seat_target='AFTER_ALIGHTING_ESTIMATE' THEN
        INSERT INTO alighting_forecast_evaluation_work(vehicle_observation_id,target_stop_order,target_kind,
            label_version,route_version_id)
        VALUES(NEW.vehicle_observation_id,NEW.target_stop_order,'AFTER_ALIGHTING_ESTIMATE',
            NEW.label_version,NEW.route_version_id) ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE OR REPLACE TRIGGER trg_alighting_evaluation_work AFTER INSERT ON seat_forecast
    FOR EACH ROW EXECUTE FUNCTION register_alighting_evaluation_work();

-- V80__incremental_alighting_settlement.sql:101-136
CREATE OR REPLACE FUNCTION enqueue_alighting_settlement_batch(p_observation_id bigint,p_label varchar,p_due timestamptz)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF p_label NOT IN ('alighting-confirmed-capacity-snapshot-v3','les-confirmed-capacity-snapshot-v3') THEN RETURN; END IF;
    INSERT INTO alighting_settlement_batch_work(batch_id,label_version,route_version_id,route_id,
        source_date,source_at,last_enqueue_xid,retry_at)
    SELECT b.id,p_label,b.route_version_id,v.route_id,
        (CASE WHEN p_label='alighting-confirmed-capacity-snapshot-v3'
            THEN coalesce(b.input_confirmed_at,b.response_received_at) ELSE b.response_received_at END AT TIME ZONE 'Asia/Seoul')::date,
        CASE WHEN p_label='alighting-confirmed-capacity-snapshot-v3'
            THEN coalesce(b.input_confirmed_at,b.response_received_at) ELSE b.response_received_at END,
        pg_current_xact_id(),p_due
    FROM vehicle_observation o JOIN observation_batch b ON b.id=o.observation_batch_id
    JOIN route_version v ON v.id=b.route_version_id WHERE o.id=p_observation_id
    ON CONFLICT(batch_id,label_version) DO UPDATE
    SET enqueue_generation=alighting_settlement_batch_work.enqueue_generation+1,
        last_enqueue_xid=EXCLUDED.last_enqueue_xid,
        retry_at=least(alighting_settlement_batch_work.retry_at,EXCLUDED.retry_at),
        source_date=EXCLUDED.source_date,source_at=EXCLUDED.source_at,
        route_version_id=EXCLUDED.route_version_id,route_id=EXCLUDED.route_id
    WHERE alighting_settlement_batch_work.last_enqueue_xid<>EXCLUDED.last_enqueue_xid
        OR EXCLUDED.retry_at<alighting_settlement_batch_work.retry_at
        OR (alighting_settlement_batch_work.source_at,alighting_settlement_batch_work.route_version_id)
            IS DISTINCT FROM (EXCLUDED.source_at,EXCLUDED.route_version_id);
END $$;

CREATE OR REPLACE FUNCTION register_alighting_settlement_batch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND NEW.label_version=OLD.label_version AND NEW.next_attempt_at>OLD.next_attempt_at THEN
        RETURN NEW;
    END IF;
    PERFORM enqueue_alighting_settlement_batch(NEW.vehicle_observation_id,NEW.label_version,NEW.next_attempt_at);
    RETURN NEW;
END $$;
CREATE OR REPLACE TRIGGER trg_alighting_settlement_batch AFTER INSERT OR UPDATE ON alighting_forecast_evaluation_work
    FOR EACH ROW EXECUTE FUNCTION register_alighting_settlement_batch();

-- V80__incremental_alighting_settlement.sql:138-273
CREATE OR REPLACE FUNCTION invalidate_alighting_settlement(version_keys bigint[], changed_times timestamptz[]) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE changed record;
BEGIN
    FOR changed IN
        SELECT v.route_id,d.day,min(c.changed_at) AS changed_at
        FROM unnest(version_keys,changed_times) AS c(version_id,changed_at)
        JOIN route_version v ON v.id=c.version_id
        CROSS JOIN LATERAL (
            SELECT (c.changed_at AT TIME ZONE 'Asia/Seoul')::date AS day
            UNION ALL SELECT (c.changed_at AT TIME ZONE 'Asia/Seoul')::date-1
                WHERE (c.changed_at AT TIME ZONE 'Asia/Seoul')::time<time '02:00'
        ) d
        WHERE c.changed_at IS NOT NULL AND isfinite(c.changed_at)
        GROUP BY v.route_id,d.day
        ORDER BY v.route_id,d.day
    LOOP
        INSERT INTO alighting_settlement_generation(route_id,source_date,generation)
        VALUES(changed.route_id,changed.day,1)
        ON CONFLICT(route_id,source_date) DO UPDATE
            SET generation=alighting_settlement_generation.generation+1;
        UPDATE alighting_settlement_projection SET dirty=true
            WHERE route_id=changed.route_id AND source_date=changed.day AND active AND NOT dirty
              AND cursor_at>=changed.changed_at;
    END LOOP;
END $$;

CREATE OR REPLACE FUNCTION track_alighting_batch_changes() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changed record;
BEGIN
    IF TG_OP='INSERT' THEN
        PERFORM invalidate_alighting_settlement(array_agg(route_version_id),array_agg(input_confirmed_at))
        FROM new_batches
        WHERE ingestion_origin='LIVE' AND outcome='SUCCESS_ROWS'
          AND input_confirmed_at BETWEEN response_received_at AND response_received_at+interval '90 seconds';
    ELSIF TG_OP='DELETE' THEN
        PERFORM invalidate_alighting_settlement(array_agg(route_version_id),array_agg(input_confirmed_at))
        FROM old_batches
        WHERE ingestion_origin='LIVE' AND outcome='SUCCESS_ROWS'
          AND input_confirmed_at BETWEEN response_received_at AND response_received_at+interval '90 seconds';
    ELSE
        PERFORM invalidate_alighting_settlement(array_agg(i.route_version_id),array_agg(i.input_confirmed_at))
        FROM (
            SELECT side.route_version_id,side.input_confirmed_at,side.response_received_at,side.ingestion_origin,side.outcome
            FROM new_batches n JOIN old_batches o USING(id)
            CROSS JOIN LATERAL (VALUES
                (o.route_version_id,o.input_confirmed_at,o.response_received_at,o.ingestion_origin,o.outcome),
                (n.route_version_id,n.input_confirmed_at,n.response_received_at,n.ingestion_origin,n.outcome)
            ) AS side(route_version_id,input_confirmed_at,response_received_at,ingestion_origin,outcome)
            WHERE (n.route_version_id,n.input_confirmed_at,n.response_received_at,n.ingestion_origin,n.outcome,n.stored_rows)
                IS DISTINCT FROM
                (o.route_version_id,o.input_confirmed_at,o.response_received_at,o.ingestion_origin,o.outcome,o.stored_rows)
        ) i
        WHERE i.ingestion_origin='LIVE' AND i.outcome='SUCCESS_ROWS'
          AND i.input_confirmed_at BETWEEN i.response_received_at AND i.response_received_at+interval '90 seconds';
        FOR changed IN
            SELECT min(o.id) AS observation_id,q.label_version,min(q.retry_at) AS due
            FROM new_batches n JOIN old_batches prior USING(id)
            JOIN alighting_settlement_batch_work q ON q.batch_id=n.id
            JOIN vehicle_observation o ON o.observation_batch_id=n.id
            WHERE (n.input_confirmed_at,n.response_received_at,n.route_version_id)
                IS DISTINCT FROM (prior.input_confirmed_at,prior.response_received_at,prior.route_version_id)
            GROUP BY n.id,q.label_version
        LOOP PERFORM enqueue_alighting_settlement_batch(changed.observation_id,changed.label_version,changed.due); END LOOP;
    END IF;
    RETURN NULL;
END $$;
CREATE OR REPLACE TRIGGER trg_alighting_batch_insert AFTER INSERT ON observation_batch
    REFERENCING NEW TABLE AS new_batches FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_batch_changes();
CREATE OR REPLACE TRIGGER trg_alighting_batch_update AFTER UPDATE ON observation_batch
    REFERENCING OLD TABLE AS old_batches NEW TABLE AS new_batches FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_batch_changes();
CREATE OR REPLACE TRIGGER trg_alighting_batch_delete AFTER DELETE ON observation_batch
    REFERENCING OLD TABLE AS old_batches FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_batch_changes();

CREATE OR REPLACE FUNCTION track_alighting_observation_changes() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changed record;
BEGIN
    IF TG_OP='INSERT' THEN
        PERFORM invalidate_alighting_settlement(array_agg(b.route_version_id),array_agg(b.input_confirmed_at))
        FROM (SELECT DISTINCT observation_batch_id FROM new_observations) o
        JOIN observation_batch b ON b.id=o.observation_batch_id
        WHERE b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS'
          AND b.input_confirmed_at BETWEEN b.response_received_at AND b.response_received_at+interval '90 seconds';
    ELSIF TG_OP='DELETE' THEN
        PERFORM invalidate_alighting_settlement(array_agg(b.route_version_id),array_agg(b.input_confirmed_at))
        FROM (SELECT DISTINCT observation_batch_id FROM old_observations) o
        JOIN observation_batch b ON b.id=o.observation_batch_id
        WHERE b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS'
          AND b.input_confirmed_at BETWEEN b.response_received_at AND b.response_received_at+interval '90 seconds';
    ELSE
        PERFORM invalidate_alighting_settlement(array_agg(b.route_version_id),array_agg(b.input_confirmed_at))
        FROM (
            SELECT DISTINCT side.batch_id
            FROM new_observations n JOIN old_observations o USING(id)
            CROSS JOIN LATERAL (VALUES (o.observation_batch_id),(n.observation_batch_id)) AS side(batch_id)
            WHERE (n.observation_batch_id,n.route_version_id,n.vehicle_id,n.passed_stop_order,n.stop_order,
                   n.running_state,n.remaining_seats,n.seat_unknown_reason,n.crowd_level)
                IS DISTINCT FROM
                  (o.observation_batch_id,o.route_version_id,o.vehicle_id,o.passed_stop_order,o.stop_order,
                   o.running_state,o.remaining_seats,o.seat_unknown_reason,o.crowd_level)
        ) x JOIN observation_batch b ON b.id=x.batch_id
        WHERE b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS'
          AND b.input_confirmed_at BETWEEN b.response_received_at AND b.response_received_at+interval '90 seconds';
        FOR changed IN
            SELECT n.id AS observation_id,w.label_version,w.next_attempt_at AS due
            FROM new_observations n JOIN old_observations prior USING(id)
            JOIN alighting_forecast_evaluation_work w ON w.vehicle_observation_id=n.id
            WHERE n.observation_batch_id<>prior.observation_batch_id
        LOOP PERFORM enqueue_alighting_settlement_batch(changed.observation_id,changed.label_version,changed.due); END LOOP;
    END IF;
    RETURN NULL;
END $$;
CREATE OR REPLACE TRIGGER trg_alighting_observation_insert AFTER INSERT ON vehicle_observation
    REFERENCING NEW TABLE AS new_observations FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_observation_changes();
CREATE OR REPLACE TRIGGER trg_alighting_observation_update AFTER UPDATE ON vehicle_observation
    REFERENCING OLD TABLE AS old_observations NEW TABLE AS new_observations FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_observation_changes();
CREATE OR REPLACE TRIGGER trg_alighting_observation_delete AFTER DELETE ON vehicle_observation
    REFERENCING OLD TABLE AS old_observations FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_observation_changes();

CREATE OR REPLACE FUNCTION cleanup_alighting_settlement_batch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    WITH touched AS (
        SELECT DISTINCT o.observation_batch_id,w.label_version
        FROM deleted_work w JOIN vehicle_observation o ON o.id=w.vehicle_observation_id
        WHERE w.label_version IN ('alighting-confirmed-capacity-snapshot-v3','les-confirmed-capacity-snapshot-v3')
    ) DELETE FROM alighting_settlement_batch_work q USING touched t
        WHERE q.batch_id=t.observation_batch_id AND q.label_version=t.label_version
          AND NOT EXISTS (
              SELECT 1 FROM vehicle_observation o JOIN alighting_forecast_evaluation_work w
                  ON w.vehicle_observation_id=o.id
              WHERE o.observation_batch_id=q.batch_id AND w.label_version=q.label_version
          );
    RETURN NULL;
END $$;
CREATE OR REPLACE TRIGGER trg_alighting_settlement_batch_cleanup AFTER DELETE ON alighting_forecast_evaluation_work
    REFERENCING OLD TABLE AS deleted_work FOR EACH STATEMENT EXECUTE FUNCTION cleanup_alighting_settlement_batch();

-- 트리거가 없던 동안 바뀐 관측을 옛 정산 근거가 모르므로 처음부터 다시 만들게 한다.
UPDATE alighting_settlement_projection SET dirty = true WHERE active AND NOT dirty;

SELECT tgrelid::regclass AS table_name, tgname, tgfoid::regproc AS function_name
  FROM pg_trigger WHERE NOT tgisinternal ORDER BY 1, 2;
COMMIT;
