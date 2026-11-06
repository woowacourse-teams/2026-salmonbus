SET LOCAL lock_timeout = '5s';

CREATE OR REPLACE FUNCTION register_les_evaluation_work() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.seat_target='AFTER_ALIGHTING_ESTIMATE' THEN
        INSERT INTO les_forecast_evaluation_work(vehicle_observation_id,target_stop_order,target_kind,
            label_version,route_version_id)
        VALUES(NEW.vehicle_observation_id,NEW.target_stop_order,'AFTER_ALIGHTING_ESTIMATE',
            NEW.label_version,NEW.route_version_id)
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END $$;

DELETE FROM les_forecast_evaluation_work WHERE target_kind='DEPARTURE_ZERO';
ALTER TABLE les_forecast_evaluation_work DROP CONSTRAINT les_forecast_evaluation_work_target_kind_check,
    ADD CONSTRAINT ck_les_evaluation_work_after_alighting CHECK (target_kind='AFTER_ALIGHTING_ESTIMATE');
