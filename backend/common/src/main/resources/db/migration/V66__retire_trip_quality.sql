SET LOCAL lock_timeout = '5s';

-- 중단 조건을 품질 테이블·뷰 삭제보다 먼저 확인한다.
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

-- Worker와 정비 프로세스를 중지한 상태에서 적용한다.
-- 과거 파일의 판본 번호를 보존하고, 앞으로의 작업 잠금은 노선 행을 사용한다.
ALTER TABLE route ADD COLUMN data_revision bigint NOT NULL DEFAULT 1 CHECK (data_revision > 0);
UPDATE route SET data_revision = saved.quality_revision
FROM route_data_quality saved WHERE saved.route_id = route.id;
COMMENT ON COLUMN route.data_revision IS '기존 통계와 아카이브의 입력 판본. 편도 품질 판정으로 갱신하지 않는다.';

-- 제외 상태를 읽으며 반복 대기하던 작업을 이번 전환에서 종료한다.
INSERT INTO forecast_evaluation_result
    (vehicle_observation_id, target_stop_order, route_version_id, scoring_state, scored_at)
SELECT pending.vehicle_observation_id, pending.target_stop_order, pending.route_version_id,
       'QUALITY_EXCLUDED', CURRENT_TIMESTAMP
FROM forecast_evaluation_pending pending
JOIN vehicle_observation source ON source.id = pending.vehicle_observation_id
WHERE source.historical_exclusion IS NOT NULL
ON CONFLICT (vehicle_observation_id, target_stop_order) DO NOTHING;

DELETE FROM forecast_evaluation_work work USING vehicle_observation source
WHERE work.vehicle_observation_id = source.id AND source.historical_exclusion IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM forecast_evaluation_pending pending
      WHERE pending.vehicle_observation_id = work.vehicle_observation_id
  );

DELETE FROM stop_demand_pending_sample sample
WHERE EXISTS (
    SELECT 1 FROM vehicle_observation observation
    WHERE observation.id IN (sample.prediction_observation_id, sample.arrival_observation_id)
      AND observation.historical_exclusion IS NOT NULL
);

ALTER TABLE vehicle_observation DROP CONSTRAINT ck_observation_seat_quality_status;
ALTER TABLE vehicle_observation ADD CONSTRAINT ck_observation_seat_quality_status CHECK (
    (reported_remaining_seats IS NULL AND seat_quality_status IS NULL)
    OR (
        reported_remaining_seats >= 0 AND reported_remaining_seats IS NOT NULL
        AND seat_quality_status IN ('REVIEW', 'OUTLIER', 'HISTORICAL') AND seat_quality_status IS NOT NULL
        AND remaining_seats IS NULL
    )
) NOT VALID;

-- 앞선 수집 원값과 판정은 그대로 보관한다. 과거 제외 관측도 원값을 남기고 운영 좌석을 비운다.
UPDATE vehicle_observation
SET reported_remaining_seats = remaining_seats,
    remaining_seats = NULL,
    seat_unknown_reason = 'REPORTED_UNKNOWN',
    seat_quality_status = 'HISTORICAL'
WHERE historical_exclusion IS NOT NULL AND remaining_seats IS NOT NULL;

UPDATE vehicle_observation SET vehicle_trip_key = historical_trip_id
WHERE historical_trip_id IS NOT NULL AND vehicle_trip_key IS DISTINCT FROM historical_trip_id;

DROP VIEW les_quality_eligible_evaluation;
DROP VIEW les_quality_eligible_metric;
DROP VIEW training_eligible_seat_forecast;
DROP VIEW training_eligible_stop_demand_statistics;
DROP VIEW quality_training_seat_forecast;
DROP VIEW quality_calibration_seat_forecast;
DROP VIEW quality_eligible_seat_forecast;
DROP VIEW one_stop_quality_eligible_seat_forecast;
DROP VIEW forecast_eligible_observation;
DROP VIEW forecast_observation_quality;
DROP VIEW one_stop_operational_seat_forecast;
DROP VIEW operational_eligible_observation;
DROP VIEW operational_observation;

ALTER TABLE vehicle_observation
    DROP COLUMN historical_trip_id,
    DROP COLUMN historical_exclusion;

CREATE VIEW operational_observation AS
SELECT observation.id, observation.observation_batch_id, observation.route_version_id,
       observation.source_row_number, observation.vehicle_id, observation.vehicle_trip_key,
       observation.plate_number, observation.stop_order, observation.stop_id, observation.running_state,
       observation.remaining_seats, observation.crowd_level, observation.vehicle_type, observation.route_type,
       observation.tagless, observation.seat_unknown_reason, observation.passed_stop_order,
       CASE WHEN version.turn_sequence IS NOT NULL AND (
           observation.stop_order > version.turn_sequence
           OR (observation.stop_order = version.turn_sequence AND observation.running_state = 2)
           OR (observation.stop_order = 1 AND observation.running_state = 1))
           THEN 1 ELSE 0 END::bigint AS quality_direction
FROM vehicle_observation observation
LEFT JOIN route_version version ON version.id = observation.route_version_id;

CREATE VIEW one_stop_operational_seat_forecast AS
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.route_version_id,
       forecast.stops_to_target, forecast.model_deployment_id, forecast.demand_statistics_revision,
       forecast.seat_full_chance_raw, forecast.seat_full_chance, forecast.expected_seats, forecast.generated_at,
       evaluation.scoring_state, evaluation.arrival_observation_id, evaluation.seats_on_arrival,
       evaluation.scored_at, forecast.quality_revision
FROM seat_forecast forecast
JOIN forecast_evaluation_result evaluation USING (vehicle_observation_id, target_stop_order)
JOIN operational_observation source ON source.id = forecast.vehicle_observation_id
LEFT JOIN operational_observation arrival ON arrival.id = evaluation.arrival_observation_id
WHERE forecast.stops_to_target = 1
AND source.remaining_seats IS NOT NULL
AND (evaluation.arrival_observation_id IS NULL OR (
    arrival.remaining_seats IS NOT NULL
    AND evaluation.arrival_route_version_id = source.route_version_id
    AND evaluation.arrival_vehicle_id IS NOT DISTINCT FROM source.vehicle_id
    AND evaluation.arrival_quality_direction = source.quality_direction
));

CREATE VIEW training_eligible_seat_forecast AS
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.route_version_id,
       forecast.stops_to_target, forecast.model_deployment_id, forecast.demand_statistics_revision,
       forecast.seat_full_chance_raw, forecast.seat_full_chance, forecast.expected_seats, forecast.generated_at,
       evaluation.scoring_state, evaluation.arrival_observation_id, evaluation.seats_on_arrival,
       evaluation.scored_at, forecast.quality_revision
FROM seat_forecast forecast
JOIN forecast_evaluation evaluation USING (vehicle_observation_id, target_stop_order)
JOIN operational_observation source ON source.id = forecast.vehicle_observation_id
JOIN operational_observation arrival ON arrival.id = evaluation.arrival_observation_id
JOIN route_version version ON version.id = forecast.route_version_id
JOIN route route_state ON route_state.id = version.route_id
JOIN model_deployment deployment ON deployment.id = forecast.model_deployment_id
WHERE evaluation.scoring_state = 'SETTLED'
  AND source.remaining_seats IS NOT NULL AND arrival.remaining_seats IS NOT NULL
  AND arrival.route_version_id = source.route_version_id
  AND arrival.vehicle_id IS NOT DISTINCT FROM source.vehicle_id
  AND arrival.quality_direction = source.quality_direction
  AND forecast.quality_revision = route_state.data_revision
  AND NOT EXISTS (
      SELECT 1 FROM model_training_exclusion excluded
      WHERE excluded.observed_model_deployment_id = deployment.id
        AND excluded.release_id = deployment.release_id
        AND excluded.bundle_digest = deployment.bundle_digest
        AND excluded.calculation_version = deployment.calculation_version
        AND excluded.observed_activated_at = deployment.activated_at
  );

CREATE VIEW training_eligible_stop_demand_statistics AS
SELECT statistics.route_version_id, statistics.stop_order, statistics.time_slot, statistics.calculation_version,
       statistics.revision, statistics.average_fill_rate, statistics.average_net_boarding_rate,
       statistics.sample_count, statistics.data_until, statistics.computed_at, statistics.day_count,
       statistics.quality_revision
FROM stop_demand_statistics statistics
JOIN route_version version ON version.id = statistics.route_version_id
JOIN route route_state ON route_state.id = version.route_id
WHERE statistics.quality_revision = route_state.data_revision
AND NOT EXISTS (
    SELECT 1 FROM statistics_training_exclusion excluded
    WHERE excluded.route_version_id = statistics.route_version_id
      AND excluded.calculation_version = statistics.calculation_version
      AND excluded.revision = statistics.revision AND excluded.data_until = statistics.data_until
      AND excluded.computed_at = statistics.computed_at
)
AND NOT EXISTS (
    SELECT 1 FROM model_training_exclusion temporary
    WHERE temporary.classification = 'TEMPORARY_RELEASE' AND temporary.final_cutover_at IS NULL
      AND statistics.calculation_version IN (temporary.calculation_version, 'observed-max-capacity-v1')
      AND statistics.computed_at >= temporary.observed_activated_at
);

DROP TABLE observation_trip_assignment;
DROP TABLE vehicle_one_way_trip;
DROP TABLE trip_quality_rebuild;
DROP TABLE route_version_quality_policy;
DROP TABLE route_data_quality;

-- 수집 이후 품질 재조사 상태는 읽지 않는다. 원본 결과와 기존 판본은 보존한다.
CREATE VIEW les_quality_eligible_evaluation AS
SELECT result.* FROM les_forecast_evaluation_result result
JOIN route_version v ON v.id=result.route_version_id
JOIN route r ON r.id=v.route_id AND r.data_revision=result.quality_revision
JOIN operational_observation source ON source.id=result.vehicle_observation_id
WHERE source.remaining_seats IS NOT NULL;
CREATE VIEW les_quality_eligible_metric AS
SELECT result.* FROM les_forecast_evaluation_metric result
JOIN route_version v ON v.id=result.route_version_id
JOIN route r ON r.id=v.route_id AND r.data_revision=result.quality_revision
JOIN operational_observation source ON source.id=result.vehicle_observation_id
WHERE source.remaining_seats IS NOT NULL;
