SET LOCAL lock_timeout = '5s';

ALTER TABLE vehicle_observation
    ADD COLUMN historical_trip_id varchar(120),
    ADD COLUMN historical_exclusion varchar(12),
    ADD CONSTRAINT ck_observation_historical_exclusion
        CHECK (historical_exclusion IN ('INELIGIBLE', 'CONFIRMED')) NOT VALID;
