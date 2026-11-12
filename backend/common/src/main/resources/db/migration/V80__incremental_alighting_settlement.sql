SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='120s';

CREATE TABLE alighting_settlement_generation (
    route_id bigint NOT NULL REFERENCES route(id) ON DELETE CASCADE,
    source_date date NOT NULL,
    generation bigint NOT NULL DEFAULT 0,
    PRIMARY KEY(route_id,source_date)
);

CREATE TABLE alighting_settlement_projection (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    route_id bigint NOT NULL REFERENCES route(id) ON DELETE CASCADE,
    source_date date NOT NULL,
    layout_hash varchar(64) NOT NULL,
    layout jsonb NOT NULL,
    data_revision bigint NOT NULL,
    observation_limit integer NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    input_generation bigint NOT NULL DEFAULT -1,
    facts_version bigint NOT NULL DEFAULT 0,
    cursor_at timestamptz NOT NULL,
    coverage_at timestamptz NOT NULL,
    cursor_batch_id bigint NOT NULL DEFAULT 0,
    next_input_at timestamptz,
    observation_count bigint NOT NULL DEFAULT 0,
    incomplete_at timestamptz,
    overflow_at timestamptz,
    state bytea NOT NULL,
    active boolean NOT NULL DEFAULT true,
    dirty boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX ux_alighting_projection_active
    ON alighting_settlement_projection(route_id,source_date,layout_hash,data_revision,observation_limit)
    WHERE active;
CREATE INDEX ix_alighting_projection_retired ON alighting_settlement_projection(id) WHERE NOT active;

CREATE TABLE alighting_settlement_observation (
    projection_id bigint NOT NULL REFERENCES alighting_settlement_projection(id) ON DELETE CASCADE,
    observation_id bigint NOT NULL,
    chain_id bigint NOT NULL,
    stop_order integer NOT NULL,
    available_at timestamptz NOT NULL,
    known_seats boolean NOT NULL,
    PRIMARY KEY(projection_id,observation_id)
);
CREATE INDEX ix_alighting_observation_target
    ON alighting_settlement_observation(projection_id,chain_id,stop_order,available_at)
    WHERE known_seats;

CREATE TABLE alighting_settlement_pass (
    projection_id bigint NOT NULL REFERENCES alighting_settlement_projection(id) ON DELETE CASCADE,
    trip_id bigint NOT NULL,
    stop_order integer NOT NULL,
    chain_id bigint NOT NULL,
    confirmed_at timestamptz NOT NULL,
    departure_date date NOT NULL,
    departure_observation_id bigint NOT NULL,
    payload bytea NOT NULL,
    PRIMARY KEY(projection_id,trip_id,stop_order)
);
CREATE INDEX ix_alighting_pass_chain ON alighting_settlement_pass(
    projection_id,chain_id,stop_order,confirmed_at,departure_observation_id);
CREATE INDEX ix_alighting_pass_previous ON alighting_settlement_pass(
    projection_id,stop_order,departure_date,confirmed_at DESC,departure_observation_id);

CREATE TABLE alighting_settlement_batch_work (
    batch_id bigint NOT NULL REFERENCES observation_batch(id) ON DELETE CASCADE,
    label_version varchar(80) NOT NULL,
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    route_id bigint NOT NULL REFERENCES route(id),
    source_date date NOT NULL,
    source_at timestamptz NOT NULL,
    enqueue_generation bigint NOT NULL DEFAULT 1,
    attempted_generation bigint NOT NULL DEFAULT 0,
    last_enqueue_xid xid8 NOT NULL,
    retry_at timestamptz NOT NULL,
    evidence jsonb NOT NULL DEFAULT '[]',
    lease_token uuid,
    lease_until timestamptz,
    PRIMARY KEY(batch_id,label_version)
);
CREATE INDEX ix_alighting_batch_work_ready ON alighting_settlement_batch_work(route_version_id,retry_at,source_at,batch_id);
CREATE INDEX ix_alighting_batch_work_date ON alighting_settlement_batch_work(route_id,source_date);

CREATE TABLE alighting_settlement_queue_scan (
    id boolean PRIMARY KEY CHECK(id),
    observation_id bigint NOT NULL DEFAULT 0,
    stop_order integer NOT NULL DEFAULT 0,
    target_kind varchar(40) NOT NULL DEFAULT '',
    label_version varchar(80) NOT NULL DEFAULT '',
    complete boolean NOT NULL DEFAULT false
);
INSERT INTO alighting_settlement_queue_scan(id) VALUES(true);

CREATE INDEX ix_batch_settlement_cursor
    ON observation_batch(route_version_id,input_confirmed_at,id)
    INCLUDE(response_received_at,stored_rows)
    WHERE ingestion_origin='LIVE' AND outcome='SUCCESS_ROWS' AND input_confirmed_at IS NOT NULL;

CREATE INDEX ix_alighting_legacy_settlement_due
    ON alighting_forecast_evaluation_work(route_version_id,next_attempt_at,vehicle_observation_id,target_stop_order,target_kind,label_version)
    WHERE label_version NOT IN ('alighting-confirmed-capacity-snapshot-v3','les-confirmed-capacity-snapshot-v3');

CREATE FUNCTION enqueue_alighting_settlement_batch(p_observation_id bigint,p_label varchar,p_due timestamptz)
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

CREATE FUNCTION register_alighting_settlement_batch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND NEW.label_version=OLD.label_version AND NEW.next_attempt_at>OLD.next_attempt_at THEN
        RETURN NEW;
    END IF;
    PERFORM enqueue_alighting_settlement_batch(NEW.vehicle_observation_id,NEW.label_version,NEW.next_attempt_at);
    RETURN NEW;
END $$;
CREATE TRIGGER trg_alighting_settlement_batch AFTER INSERT OR UPDATE ON alighting_forecast_evaluation_work
    FOR EACH ROW EXECUTE FUNCTION register_alighting_settlement_batch();

CREATE FUNCTION invalidate_alighting_settlement(version_key bigint, changed_at timestamptz) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE route_key bigint; day_key date;
BEGIN
    IF changed_at IS NULL OR NOT isfinite(changed_at) THEN RETURN; END IF;
    SELECT route_id INTO route_key FROM route_version WHERE id=version_key;
    IF route_key IS NULL THEN RETURN; END IF;
    FOR day_key IN
        SELECT (changed_at AT TIME ZONE 'Asia/Seoul')::date
        UNION ALL SELECT (changed_at AT TIME ZONE 'Asia/Seoul')::date-1
            WHERE (changed_at AT TIME ZONE 'Asia/Seoul')::time<time '02:00'
        ORDER BY 1
    LOOP
        INSERT INTO alighting_settlement_generation(route_id,source_date,generation) VALUES(route_key,day_key,1)
        ON CONFLICT(route_id,source_date) DO UPDATE
            SET generation=alighting_settlement_generation.generation+1;
        UPDATE alighting_settlement_projection SET dirty=true
            WHERE route_id=route_key AND source_date=day_key AND active AND cursor_at>=changed_at;
    END LOOP;
END $$;

CREATE FUNCTION track_alighting_batch_changes() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changed record;
BEGIN
    IF TG_OP='INSERT' THEN
        FOR changed IN SELECT DISTINCT route_version_id,input_confirmed_at FROM new_batches
            WHERE ingestion_origin='LIVE' AND outcome='SUCCESS_ROWS'
              AND input_confirmed_at BETWEEN response_received_at AND response_received_at+interval '90 seconds'
            ORDER BY route_version_id,input_confirmed_at
        LOOP PERFORM invalidate_alighting_settlement(changed.route_version_id,changed.input_confirmed_at); END LOOP;
    ELSIF TG_OP='DELETE' THEN
        FOR changed IN SELECT DISTINCT route_version_id,input_confirmed_at FROM old_batches
            WHERE ingestion_origin='LIVE' AND outcome='SUCCESS_ROWS'
              AND input_confirmed_at BETWEEN response_received_at AND response_received_at+interval '90 seconds'
            ORDER BY route_version_id,input_confirmed_at
        LOOP PERFORM invalidate_alighting_settlement(changed.route_version_id,changed.input_confirmed_at); END LOOP;
    ELSE
        FOR changed IN
            WITH modified AS (
                SELECT n.id FROM new_batches n JOIN old_batches o USING(id)
                WHERE (n.route_version_id,n.input_confirmed_at,n.response_received_at,n.ingestion_origin,n.outcome,n.stored_rows)
                    IS DISTINCT FROM
                    (o.route_version_id,o.input_confirmed_at,o.response_received_at,o.ingestion_origin,o.outcome,o.stored_rows)
            ), inputs AS (
                SELECT b.* FROM old_batches b JOIN modified m USING(id)
                UNION ALL SELECT b.* FROM new_batches b JOIN modified m USING(id)
            ) SELECT DISTINCT route_version_id,input_confirmed_at FROM inputs
                WHERE ingestion_origin='LIVE' AND outcome='SUCCESS_ROWS'
                  AND input_confirmed_at BETWEEN response_received_at AND response_received_at+interval '90 seconds'
            ORDER BY route_version_id,input_confirmed_at
        LOOP PERFORM invalidate_alighting_settlement(changed.route_version_id,changed.input_confirmed_at); END LOOP;
    END IF;
    IF TG_OP='UPDATE' THEN
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
CREATE TRIGGER trg_alighting_batch_insert AFTER INSERT ON observation_batch
    REFERENCING NEW TABLE AS new_batches FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_batch_changes();
CREATE TRIGGER trg_alighting_batch_update AFTER UPDATE ON observation_batch
    REFERENCING OLD TABLE AS old_batches NEW TABLE AS new_batches FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_batch_changes();
CREATE TRIGGER trg_alighting_batch_delete AFTER DELETE ON observation_batch
    REFERENCING OLD TABLE AS old_batches FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_batch_changes();

CREATE FUNCTION track_alighting_observation_changes() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changed record;
BEGIN
    IF TG_OP='INSERT' THEN
        FOR changed IN SELECT DISTINCT b.route_version_id,b.input_confirmed_at
            FROM new_observations o JOIN observation_batch b ON b.id=o.observation_batch_id
            WHERE b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS'
              AND b.input_confirmed_at BETWEEN b.response_received_at AND b.response_received_at+interval '90 seconds'
            ORDER BY b.route_version_id,b.input_confirmed_at
        LOOP PERFORM invalidate_alighting_settlement(changed.route_version_id,changed.input_confirmed_at); END LOOP;
    ELSIF TG_OP='DELETE' THEN
        FOR changed IN SELECT DISTINCT b.route_version_id,b.input_confirmed_at
            FROM old_observations o JOIN observation_batch b ON b.id=o.observation_batch_id
            WHERE b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS'
              AND b.input_confirmed_at BETWEEN b.response_received_at AND b.response_received_at+interval '90 seconds'
            ORDER BY b.route_version_id,b.input_confirmed_at
        LOOP PERFORM invalidate_alighting_settlement(changed.route_version_id,changed.input_confirmed_at); END LOOP;
    ELSE
        FOR changed IN
            WITH modified AS (
                SELECT n.id FROM new_observations n JOIN old_observations o USING(id)
                WHERE (n.observation_batch_id,n.route_version_id,n.vehicle_id,n.passed_stop_order,n.stop_order,
                       n.running_state,n.remaining_seats,n.seat_unknown_reason,n.crowd_level)
                    IS DISTINCT FROM
                      (o.observation_batch_id,o.route_version_id,o.vehicle_id,o.passed_stop_order,o.stop_order,
                       o.running_state,o.remaining_seats,o.seat_unknown_reason,o.crowd_level)
            ), batches AS (
                SELECT o.observation_batch_id FROM old_observations o JOIN modified m USING(id)
                UNION SELECT o.observation_batch_id FROM new_observations o JOIN modified m USING(id)
            ) SELECT DISTINCT b.route_version_id,b.input_confirmed_at FROM batches x JOIN observation_batch b
                ON b.id=x.observation_batch_id WHERE b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS'
                  AND b.input_confirmed_at BETWEEN b.response_received_at AND b.response_received_at+interval '90 seconds'
            ORDER BY b.route_version_id,b.input_confirmed_at
        LOOP PERFORM invalidate_alighting_settlement(changed.route_version_id,changed.input_confirmed_at); END LOOP;
    END IF;
    IF TG_OP='UPDATE' THEN
        FOR changed IN
            SELECT n.id AS observation_id,w.label_version,w.next_attempt_at AS due
            FROM new_observations n JOIN old_observations prior USING(id)
            JOIN alighting_forecast_evaluation_work w ON w.vehicle_observation_id=n.id
            WHERE n.observation_batch_id<>prior.observation_batch_id
        LOOP PERFORM enqueue_alighting_settlement_batch(changed.observation_id,changed.label_version,changed.due); END LOOP;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER trg_alighting_observation_insert AFTER INSERT ON vehicle_observation
    REFERENCING NEW TABLE AS new_observations FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_observation_changes();
CREATE TRIGGER trg_alighting_observation_update AFTER UPDATE ON vehicle_observation
    REFERENCING OLD TABLE AS old_observations NEW TABLE AS new_observations FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_observation_changes();
CREATE TRIGGER trg_alighting_observation_delete AFTER DELETE ON vehicle_observation
    REFERENCING OLD TABLE AS old_observations FOR EACH STATEMENT EXECUTE FUNCTION track_alighting_observation_changes();

CREATE FUNCTION cleanup_alighting_settlement_batch() RETURNS trigger LANGUAGE plpgsql AS $$
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
CREATE TRIGGER trg_alighting_settlement_batch_cleanup AFTER DELETE ON alighting_forecast_evaluation_work
    REFERENCING OLD TABLE AS deleted_work FOR EACH STATEMENT EXECUTE FUNCTION cleanup_alighting_settlement_batch();
