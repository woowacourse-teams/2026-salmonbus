-- 검증이 끝난 중지 상태에서만 최종 저장 구조를 적용한다.
-- PostgreSQL에서 이 파일 전체와 Flyway 이력 기록은 같은 트랜잭션으로 실행된다.
SET LOCAL lock_timeout = '5s';
DO $body$
DECLARE
    table_name text;
    has_rows boolean;
    source_empty boolean := true;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'daily_call_quota','route','route_version','route_stop','observation_batch','vehicle_observation',
        'seat_forecast','stop_demand_statistics','same_day_full_outcomes','model_deployment',
        'vehicle_one_way_trip','trip_quality_rebuild','forecast_publication','forecast_evaluation',
        'demand_statistics_version','route_data_quality','route_version_quality_policy','observation_trip_assignment',
        'model_active_slot','model_activation_request','model_training_exclusion','statistics_training_exclusion',
        'training_model_release_exclusion','training_statistics_generation_exclusion',
        'historical_import_batch','historical_import_dataset_seal','historical_import_route_boundary',
        'historical_import_route_binding','historical_import_shard','historical_import_record','migration_source_record',
        'forecast_cutover_control','temporary_statistics_generation_freeze','sal134_transition_progress',
        'stop_demand_seed_import','stop_demand_seed_hourly_total','stop_demand_seed_generation'
    ] LOOP
        IF to_regclass(format('%I.%I', current_schema(), table_name)) IS NOT NULL THEN
            EXECUTE format('LOCK TABLE %I IN ACCESS EXCLUSIVE MODE', table_name);
            -- V17이 만든 비어 있는 활성 슬롯은 새 DB에도 존재한다.
            IF table_name = 'model_active_slot' THEN
                SELECT EXISTS(SELECT 1 FROM model_active_slot WHERE model_deployment_id IS NOT NULL OR version <> 0) INTO has_rows;
            ELSE
                EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I)', table_name) INTO has_rows;
            END IF;
            source_empty := source_empty AND NOT has_rows;
        END IF;
    END LOOP;
    IF NOT source_empty AND NOT EXISTS (SELECT 1 FROM sal134_transition WHERE verified_at IS NOT NULL) THEN
        RAISE EXCEPTION 'SAL-134: backfill 검증이 완료되지 않았습니다. 앱을 중지하고 전환 도구의 prepare, backfill, verify를 실행하세요.';
    END IF;
END;
$body$;

-- 외부 학습 조회의 이름, 컬럼 순서, 타입을 유지한다. publication_id와 평가 근거는 공개하지 않는다.
-- 기존 view를 먼저 교체해야 아래의 컬럼 삭제가 의존성을 위반하지 않는다.
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
           OR (observation.stop_order = 1 AND observation.running_state <> 2))
           THEN 1 ELSE 0 END::bigint AS quality_direction,
       ((trip.status IS NULL OR trip.status = 'ELIGIBLE')
        AND (observation.remaining_seats IS NULL OR observation.remaining_seats <= 70)
        AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild rebuild
            WHERE rebuild.route_version_id = observation.route_version_id AND NOT rebuild.completed
              AND (rebuild.vehicle_id = '' OR rebuild.vehicle_id = observation.vehicle_id))) AS forecast_eligible
FROM vehicle_observation observation
LEFT JOIN observation_trip_assignment assignment ON assignment.observation_id = observation.id
LEFT JOIN vehicle_one_way_trip trip ON trip.id = COALESCE(assignment.trip_id, observation.vehicle_trip_key)
LEFT JOIN route_version version ON version.id = observation.route_version_id;

CREATE OR REPLACE VIEW forecast_eligible_observation AS
SELECT id, observation_batch_id, route_version_id, source_row_number, vehicle_id, vehicle_trip_key,
       plate_number, stop_order, stop_id, running_state, remaining_seats, crowd_level, vehicle_type,
       route_type, tagless, seat_unknown_reason, passed_stop_order, quality_direction, forecast_eligible
FROM forecast_observation_quality WHERE forecast_eligible;

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
LEFT JOIN observation_trip_assignment arrival_assignment ON arrival_assignment.observation_id = evaluation.arrival_observation_id
LEFT JOIN vehicle_one_way_trip arrival_trip ON arrival_trip.id = COALESCE(arrival_assignment.trip_id, evaluation.arrival_vehicle_trip_key)
WHERE evaluation.arrival_observation_id IS NULL OR (
    evaluation.arrival_route_version_id = source.route_version_id
    AND evaluation.arrival_vehicle_id IS NOT DISTINCT FROM source.vehicle_id
    AND evaluation.arrival_quality_direction = source.quality_direction
    AND (evaluation.arrival_remaining_seats IS NULL OR evaluation.arrival_remaining_seats <= 70)
    AND (arrival_trip.status IS NULL OR arrival_trip.status = 'ELIGIBLE')
    AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild rebuild
        WHERE rebuild.route_version_id = evaluation.arrival_route_version_id AND NOT rebuild.completed
          AND (rebuild.vehicle_id = '' OR rebuild.vehicle_id = evaluation.arrival_vehicle_id))
);

CREATE OR REPLACE VIEW quality_calibration_seat_forecast AS
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.route_version_id,
       forecast.stops_to_target, forecast.model_deployment_id, forecast.demand_statistics_revision,
       forecast.seat_full_chance_raw, forecast.seat_full_chance, forecast.expected_seats, forecast.generated_at,
       forecast.scoring_state, forecast.arrival_observation_id, forecast.seats_on_arrival,
       forecast.scored_at, forecast.quality_revision
FROM quality_eligible_seat_forecast forecast
JOIN route_version version ON version.id = forecast.route_version_id
JOIN route_data_quality quality ON quality.route_id = version.route_id
WHERE forecast.quality_revision = quality.quality_revision;

CREATE OR REPLACE VIEW quality_training_seat_forecast AS
SELECT vehicle_observation_id, target_stop_order, route_version_id, stops_to_target, model_deployment_id,
       demand_statistics_revision, seat_full_chance_raw, seat_full_chance, expected_seats, generated_at,
       scoring_state, arrival_observation_id, seats_on_arrival, scored_at, quality_revision
FROM quality_calibration_seat_forecast WHERE scoring_state = 'SETTLED';

CREATE OR REPLACE VIEW training_eligible_seat_forecast AS
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.route_version_id,
       forecast.stops_to_target, forecast.model_deployment_id, forecast.demand_statistics_revision,
       forecast.seat_full_chance_raw, forecast.seat_full_chance, forecast.expected_seats, forecast.generated_at,
       forecast.scoring_state, forecast.arrival_observation_id, forecast.seats_on_arrival,
       forecast.scored_at, forecast.quality_revision
FROM quality_training_seat_forecast forecast
JOIN model_deployment deployment ON deployment.id = forecast.model_deployment_id
WHERE NOT EXISTS (SELECT 1 FROM model_training_exclusion excluded
    WHERE excluded.observed_model_deployment_id = deployment.id
      AND excluded.release_id = deployment.release_id
      AND excluded.bundle_digest = deployment.bundle_digest
      AND excluded.calculation_version = deployment.calculation_version
      AND excluded.observed_activated_at = deployment.activated_at);

CREATE OR REPLACE VIEW training_eligible_stop_demand_statistics AS
SELECT statistics.route_version_id, statistics.stop_order, statistics.time_slot, statistics.calculation_version,
       statistics.revision, statistics.average_fill_rate, statistics.average_net_boarding_rate,
       statistics.sample_count, statistics.data_until, statistics.computed_at, statistics.day_count,
       statistics.quality_revision
FROM stop_demand_statistics statistics
JOIN route_version version ON version.id = statistics.route_version_id
JOIN route_data_quality quality ON quality.route_id = version.route_id
WHERE statistics.quality_revision = quality.quality_revision
AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild rebuild WHERE rebuild.route_version_id = statistics.route_version_id AND NOT rebuild.completed)
AND NOT EXISTS (SELECT 1 FROM statistics_training_exclusion excluded
    WHERE excluded.route_version_id = statistics.route_version_id
      AND excluded.calculation_version = statistics.calculation_version
      AND excluded.revision = statistics.revision
      AND excluded.data_until = statistics.data_until
      AND excluded.computed_at = statistics.computed_at)
AND NOT EXISTS (SELECT 1 FROM model_training_exclusion temporary
    WHERE temporary.classification = 'TEMPORARY_RELEASE'
      AND temporary.final_cutover_at IS NULL
      AND statistics.calculation_version IN (temporary.calculation_version, 'observed-max-capacity-v1')
      AND statistics.computed_at >= temporary.observed_activated_at);

DROP FUNCTION refresh_trip_quality_training_views();
ALTER TABLE seat_forecast ALTER COLUMN publication_id SET NOT NULL;
ALTER TABLE stop_demand_statistics ADD CONSTRAINT fk_statistics_version
    FOREIGN KEY (route_version_id, calculation_version, revision)
    REFERENCES demand_statistics_version(route_version_id, calculation_version, revision);
ALTER TABLE seat_forecast
    DROP COLUMN scoring_state,
    DROP COLUMN arrival_observation_id,
    DROP COLUMN seats_on_arrival,
    DROP COLUMN scored_at;
ALTER TABLE observation_batch DROP COLUMN forecast_completed_at;
ALTER TABLE route DROP COLUMN quality_revision;
ALTER TABLE route_version
    DROP COLUMN maximum_observation_gap_seconds,
    DROP COLUMN observation_gap_evidence;
-- 발행 여부는 forecast_publication으로 확인한다. 입력 확정은 평가 근거 보호에도 사용한다.
CREATE INDEX ix_batch_forecast_candidates ON observation_batch(route_version_id,response_received_at,id)
    WHERE response_received_at IS NOT NULL AND outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY');

DO $body$
DECLARE table_name text;
BEGIN
    FOR table_name IN SELECT event_object_table FROM information_schema.triggers
        WHERE trigger_schema = current_schema() AND trigger_name = 'invalidate_sal134_verification'
        GROUP BY event_object_table
    LOOP
        EXECUTE format('DROP TRIGGER invalidate_sal134_verification ON %I', table_name);
    END LOOP;
END;
$body$;
DROP FUNCTION invalidate_sal134_verification();
