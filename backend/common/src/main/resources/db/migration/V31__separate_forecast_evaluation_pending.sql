-- 평가/예보 writer를 중지한 상태에서 적용한다. 구 버전 writer와 혼용할 수 없다.
-- 완료 이력은 복사하지 않는다. 대기 행 이동만 수행하며 시간 초과 시 전체 전환을 롤백한다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE forecast_evaluation RENAME TO forecast_evaluation_result;
CREATE TABLE forecast_evaluation_pending (
    vehicle_observation_id bigint NOT NULL,
    target_stop_order integer NOT NULL,
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    PRIMARY KEY (vehicle_observation_id, target_stop_order),
    FOREIGN KEY (vehicle_observation_id, route_version_id) REFERENCES vehicle_observation(id, route_version_id),
    FOREIGN KEY (vehicle_observation_id, target_stop_order) REFERENCES seat_forecast(vehicle_observation_id, target_stop_order)
) WITH (autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_threshold = 50,
        autovacuum_vacuum_max_threshold = 10000);
CREATE INDEX ix_evaluation_pending_route ON forecast_evaluation_pending
    (route_version_id, vehicle_observation_id, target_stop_order);

WITH moved AS (
    DELETE FROM forecast_evaluation_result WHERE scoring_state = 'PENDING'
    RETURNING vehicle_observation_id, target_stop_order, route_version_id
)
INSERT INTO forecast_evaluation_pending SELECT * FROM moved;

-- NOT VALID는 큰 완료 이력을 재검사하지 않는다. 새 쓰기에는 제약을 적용한다.
ALTER TABLE forecast_evaluation_result ADD CONSTRAINT ck_evaluation_result_terminal
    CHECK (scoring_state <> 'PENDING') NOT VALID;
ALTER TABLE forecast_evaluation_result ALTER COLUMN scoring_state DROP DEFAULT;
DROP INDEX ix_evaluation_pending;
DROP INDEX IF EXISTS ix_evaluation_pending_keys;

-- 기존 통계/학습 조회의 열과 의미를 유지한다. 쓰기는 물리 테이블을 명시한다.
CREATE VIEW forecast_evaluation AS
SELECT * FROM forecast_evaluation_result
UNION ALL
SELECT vehicle_observation_id, target_stop_order, route_version_id, 'PENDING'::varchar(16),
       NULL::bigint, NULL::integer, NULL::timestamptz, NULL::timestamptz,
       NULL::bigint, NULL::varchar(40), NULL::integer, NULL::integer, NULL::integer,
       NULL::varchar(20), NULL::varchar(120), NULL::bigint
FROM forecast_evaluation_pending;

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
