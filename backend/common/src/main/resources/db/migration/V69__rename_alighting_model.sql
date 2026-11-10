SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';

-- 계산과 과거 행의 계약판은 유지하고 실행 객체의 이름만 바꾼다.
ALTER TABLE les_forecast_evaluation_work RENAME TO alighting_forecast_evaluation_work;
ALTER TABLE les_forecast_evaluation_result RENAME TO alighting_forecast_evaluation_result;
ALTER TABLE les_evaluation_archive RENAME TO alighting_evaluation_archive;
ALTER VIEW les_forecast_evaluation_metric RENAME TO alighting_forecast_evaluation_metric;
ALTER VIEW les_quality_eligible_evaluation RENAME TO alighting_quality_eligible_evaluation;
ALTER VIEW les_quality_eligible_metric RENAME TO alighting_quality_eligible_metric;
ALTER VIEW les_evaluation_status RENAME TO alighting_evaluation_status;
ALTER FUNCTION register_les_evaluation_work() RENAME TO register_alighting_evaluation_work;
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
ALTER TRIGGER trg_les_evaluation_work ON seat_forecast RENAME TO trg_alighting_evaluation_work;
DO $$ DECLARE entry record; BEGIN
    FOR entry IN SELECT conrelid::regclass AS relation,conname FROM pg_constraint
        WHERE connamespace=current_schema()::regnamespace AND conname ~ '(^|_)les_'
    LOOP
        EXECUTE format('ALTER TABLE %s RENAME CONSTRAINT %I TO %I',entry.relation,entry.conname,
            left(regexp_replace(entry.conname,'(^|_)les_','\1alighting_','g'),63));
    END LOOP;
    FOR entry IN SELECT indexrelid::regclass AS relation,c.relname AS name
        FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
        WHERE c.relnamespace=current_schema()::regnamespace AND c.relname ~ '(^|_)les_'
    LOOP
        EXECUTE format('ALTER INDEX %s RENAME TO %I',entry.relation,
            left(regexp_replace(entry.name,'(^|_)les_','\1alighting_','g'),63));
    END LOOP;
END $$;
