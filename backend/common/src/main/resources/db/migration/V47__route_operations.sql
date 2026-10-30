SET LOCAL lock_timeout = '5s';

-- Runtime route controls; source_route_id is the GBIS ID, not route_version.id.
CREATE TABLE route_operation (
    source_route_id varchar(9) PRIMARY KEY CHECK (source_route_id ~ '^[0-9]{9}$'),
    registration_state varchar(10) NOT NULL DEFAULT 'PENDING'
        CHECK (registration_state IN ('PENDING','READY','FAILED')),
    geometry_state varchar(10) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (geometry_state IN ('UNKNOWN','PENDING','READY','FAILED')),
    collection_enabled boolean NOT NULL DEFAULT true,
    service_requested boolean NOT NULL DEFAULT true,
    service_enabled boolean NOT NULL DEFAULT false,
    ever_published boolean NOT NULL DEFAULT false,
    reason varchar(40) NOT NULL DEFAULT 'ONBOARDING',
    message varchar(500) NOT NULL DEFAULT '서비스 준비 중입니다',
    source_url varchar(2000) NOT NULL DEFAULT '',
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    applied_revision bigint NOT NULL DEFAULT 0,
    enable_after timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error varchar(80) NOT NULL DEFAULT '',
    requested_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at timestamptz,
    CHECK (NOT service_requested OR collection_enabled),
    CHECK (NOT service_enabled OR (service_requested AND registration_state='READY'))
);
CREATE TABLE route_operation_change (
    request_id uuid PRIMARY KEY,
    source_route_id varchar(9) NOT NULL REFERENCES route_operation(source_route_id),
    revision bigint NOT NULL,
    action varchar(40) NOT NULL,
    actor varchar(100) NOT NULL,
    reason varchar(40) NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK (length(payload_hash)=64),
    changed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE route_service_event (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_route_id varchar(9) NOT NULL REFERENCES route_operation(source_route_id),
    reason varchar(40) NOT NULL,
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    source_url varchar(2000) NOT NULL DEFAULT '',
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE UNIQUE INDEX uq_route_service_event_open ON route_service_event(source_route_id) WHERE ended_at IS NULL;
CREATE INDEX ix_route_service_event_period ON route_service_event(source_route_id, started_at, ended_at);

-- Only seed the existing 20 collection routes. Map-only catalog rows stay unregistered.
INSERT INTO route_operation(source_route_id, registration_state, collection_enabled,
    service_requested,service_enabled,ever_published,reason,message,enable_after)
SELECT source_route_id,'READY',true,true,true,true,'NORMAL','', '-infinity'::timestamptz
FROM route WHERE source_id='GBIS' AND source_route_id IN (
 '204000057','234000050','204000070','234000886','233000392','227000038','228000184','234000051',
 '204000081','234000002','234000313','234000878','234000882','204000082','234001739','233000266',
 '204000170','234000879','228000393','200000314');

CREATE TABLE route_operation_runtime (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    collection_enabled boolean NOT NULL,
    forecast_enabled boolean NOT NULL,
    maximum_collection_routes integer NOT NULL CHECK(maximum_collection_routes >= 0),
    reported_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE OR REPLACE VIEW forecast_observation_quality AS
SELECT observation.id,
       observation.observation_batch_id,
       observation.route_version_id,
       observation.source_row_number,
       observation.vehicle_id,
       COALESCE(assignment.trip_id, observation.vehicle_trip_key)::varchar(120) AS vehicle_trip_key,
       observation.plate_number,
       observation.stop_order,
       observation.stop_id,
       observation.running_state,
       observation.remaining_seats,
       observation.crowd_level,
       observation.vehicle_type,
       observation.route_type,
       observation.tagless,
       observation.seat_unknown_reason,
       observation.passed_stop_order,
       CASE WHEN version.turn_sequence IS NOT NULL AND (
           observation.stop_order > version.turn_sequence
           OR (observation.stop_order = version.turn_sequence AND observation.running_state = 2)
           OR (observation.stop_order = 1 AND observation.running_state = 1))
           THEN 1 ELSE 0 END::bigint AS quality_direction,
       ((trip.status IS NULL OR trip.status = 'ELIGIBLE')
        AND NOT EXISTS (
            SELECT 1 FROM route_service_event event
            JOIN route route_identity ON route_identity.source_route_id=event.source_route_id
            JOIN observation_batch event_batch ON event_batch.id=observation.observation_batch_id
            WHERE route_identity.id=version.route_id
              AND event.started_at<=event_batch.response_received_at
              AND (event.ended_at IS NULL OR event.ended_at>event_batch.response_received_at))
        AND (observation.remaining_seats IS NULL OR observation.remaining_seats <= 70)
        AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild rebuild
            WHERE rebuild.route_version_id = observation.route_version_id AND NOT rebuild.completed
              AND (rebuild.vehicle_id = '' OR rebuild.vehicle_id = observation.vehicle_id))) AS forecast_eligible
FROM vehicle_observation observation
LEFT JOIN observation_trip_assignment assignment ON assignment.observation_id = observation.id
LEFT JOIN vehicle_one_way_trip trip ON trip.id = COALESCE(assignment.trip_id, observation.vehicle_trip_key)
LEFT JOIN route_version version ON version.id = observation.route_version_id;

-- A source/arrival pair must not bridge an interruption even when both endpoints look normal.
CREATE FUNCTION route_service_period_eligible(version_id bigint, from_at timestamptz, until_at timestamptz)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT from_at IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM route_service_event event
        JOIN route identity_route ON identity_route.source_id='GBIS'
            AND identity_route.source_route_id=event.source_route_id
        JOIN route_version version ON version.route_id=identity_route.id
        WHERE version.id=version_id
          AND event.started_at<=GREATEST(from_at,COALESCE(until_at,from_at))
          AND (event.ended_at IS NULL OR event.ended_at>LEAST(from_at,COALESCE(until_at,from_at)))
    );
$$;

CREATE OR REPLACE VIEW quality_eligible_seat_forecast AS
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.route_version_id,
       forecast.stops_to_target, forecast.model_deployment_id, forecast.demand_statistics_revision,
       forecast.seat_full_chance_raw, forecast.seat_full_chance, forecast.expected_seats, forecast.generated_at,
       evaluation.scoring_state, evaluation.arrival_observation_id, evaluation.seats_on_arrival,
       evaluation.scored_at, forecast.quality_revision
FROM seat_forecast forecast
JOIN forecast_evaluation evaluation ON evaluation.vehicle_observation_id = forecast.vehicle_observation_id
    AND evaluation.target_stop_order = forecast.target_stop_order
JOIN forecast_eligible_observation source ON source.id = forecast.vehicle_observation_id
JOIN observation_batch source_batch ON source_batch.id=source.observation_batch_id
LEFT JOIN observation_trip_assignment arrival_assignment ON arrival_assignment.observation_id = evaluation.arrival_observation_id
LEFT JOIN vehicle_one_way_trip arrival_trip ON arrival_trip.id = COALESCE(arrival_assignment.trip_id, evaluation.arrival_vehicle_trip_key)
WHERE route_service_period_eligible(forecast.route_version_id,source_batch.response_received_at,
    COALESCE(evaluation.arrived_at,evaluation.scored_at))
AND (evaluation.arrival_observation_id IS NULL OR (
    evaluation.arrival_route_version_id = source.route_version_id
    AND evaluation.arrival_vehicle_id IS NOT DISTINCT FROM source.vehicle_id
    AND evaluation.arrival_quality_direction = source.quality_direction
    AND (evaluation.arrival_remaining_seats IS NULL OR evaluation.arrival_remaining_seats <= 70)
    AND (arrival_trip.status IS NULL OR arrival_trip.status = 'ELIGIBLE')
    AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild rebuild
        WHERE rebuild.route_version_id = evaluation.arrival_route_version_id AND NOT rebuild.completed
          AND (rebuild.vehicle_id = '' OR rebuild.vehicle_id = evaluation.arrival_vehicle_id))
));
