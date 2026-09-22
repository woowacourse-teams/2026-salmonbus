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
CREATE INDEX ix_one_way_trip_vehicle ON vehicle_one_way_trip(route_version_id, vehicle_id);
ALTER TABLE seat_forecast ADD COLUMN quality_revision bigint NOT NULL DEFAULT 1;
ALTER TABLE stop_demand_statistics ADD COLUMN quality_revision bigint NOT NULL DEFAULT 1;
ALTER TABLE same_day_full_outcomes ADD COLUMN quality_revision bigint NOT NULL DEFAULT 1;

CREATE TABLE trip_quality_rebuild (
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    vehicle_id varchar(40) NOT NULL DEFAULT '',
    last_batch_at timestamptz,
    last_batch_id bigint NOT NULL DEFAULT 0,
    until_at timestamptz NOT NULL,
    maximum_gap_seconds integer,
    completed boolean NOT NULL DEFAULT false,
    phase varchar(20) NOT NULL DEFAULT 'DISCOVER' CHECK (phase IN ('DISCOVER','SEARCH_START','REPLAY','DONE')),
    evidence_observation_id bigint,
    anchor_observation_id bigint,
    previous_observation_id bigint,
    include_cursor boolean NOT NULL DEFAULT false,
    can_release boolean NOT NULL DEFAULT false,
    investigated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(route_version_id, vehicle_id)
);

CREATE VIEW forecast_observation_quality AS
SELECT observation.*,
       CASE WHEN version.turn_sequence IS NOT NULL AND (
           observation.stop_order > version.turn_sequence
           OR (observation.stop_order = version.turn_sequence AND observation.running_state = 2)
           OR (observation.stop_order = 1 AND observation.running_state <> 2))
           THEN 1 ELSE 0 END::bigint AS quality_direction,
       ((trip.status IS NULL OR trip.status = 'ELIGIBLE')
  AND (observation.remaining_seats IS NULL OR observation.remaining_seats <= 70)
  AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild rebuild
                  WHERE rebuild.route_version_id = observation.route_version_id AND NOT rebuild.completed
                    AND (rebuild.vehicle_id = '' OR rebuild.vehicle_id = observation.vehicle_id))) AS forecast_eligible
FROM vehicle_observation observation
LEFT JOIN vehicle_one_way_trip trip ON trip.id = observation.vehicle_trip_key
LEFT JOIN route_version version ON version.id = observation.route_version_id;

CREATE VIEW forecast_eligible_observation AS
SELECT * FROM forecast_observation_quality WHERE forecast_eligible;

CREATE VIEW quality_eligible_seat_forecast AS
SELECT forecast.*
FROM seat_forecast forecast
JOIN forecast_eligible_observation source ON source.id = forecast.vehicle_observation_id
LEFT JOIN forecast_eligible_observation arrival ON arrival.id = forecast.arrival_observation_id
WHERE forecast.arrival_observation_id IS NULL OR (arrival.id IS NOT NULL AND arrival.route_version_id = source.route_version_id
    AND arrival.vehicle_id IS NOT DISTINCT FROM source.vehicle_id
    AND arrival.quality_direction = source.quality_direction);

CREATE VIEW quality_calibration_seat_forecast AS
SELECT forecast.* FROM quality_eligible_seat_forecast forecast
JOIN route_version version ON version.id = forecast.route_version_id
JOIN route quality ON quality.id = version.route_id
WHERE forecast.quality_revision = quality.quality_revision;

CREATE VIEW quality_training_seat_forecast AS
SELECT * FROM quality_calibration_seat_forecast WHERE scoring_state = 'SETTLED';

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
