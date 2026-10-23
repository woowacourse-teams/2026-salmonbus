-- 아직 배포하지 않은 V42. 모든 예보를 평가하고 상세 저장 여부만 선택한다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- 기존 행을 재작성하지 않는다. 구 worker가 발행하는 예보도 기본값 true로 상세 저장한다.
ALTER TABLE seat_forecast ADD COLUMN evaluation_detail_required boolean NOT NULL DEFAULT true;

-- 상세를 생략한 정상 평가도 집계 재생성과 중복 반영 방지를 위해 최소 결과를 남긴다.
-- 재집계에 필요한 도착 관측을 먼저 삭제하지 못하도록 FK로 보호한다.
CREATE TABLE forecast_evaluation_compact (
    vehicle_observation_id bigint NOT NULL,
    target_stop_order integer NOT NULL,
    arrival_observation_id bigint NOT NULL REFERENCES vehicle_observation(id),
    seats_on_arrival integer NOT NULL CHECK (seats_on_arrival BETWEEN 0 AND 70),
    scored_at timestamptz NOT NULL,
    arrived_at timestamptz NOT NULL,
    arrival_vehicle_trip_key varchar(120),
    arrival_quality_direction bigint NOT NULL,
    PRIMARY KEY (vehicle_observation_id, target_stop_order),
    FOREIGN KEY (vehicle_observation_id, target_stop_order)
        REFERENCES seat_forecast(vehicle_observation_id, target_stop_order)
);

CREATE INDEX ix_evaluation_compact_arrival
    ON forecast_evaluation_compact(arrived_at, vehicle_observation_id);

CREATE OR REPLACE VIEW forecast_evaluation_pending AS
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, work.route_version_id
FROM forecast_evaluation_work work
JOIN seat_forecast forecast
  ON forecast.vehicle_observation_id = work.vehicle_observation_id
 AND forecast.route_version_id = work.route_version_id
WHERE NOT EXISTS (
    SELECT 1 FROM forecast_evaluation_result result
    WHERE result.vehicle_observation_id = forecast.vehicle_observation_id
      AND result.target_stop_order = forecast.target_stop_order
)
AND NOT EXISTS (
    SELECT 1 FROM forecast_evaluation_compact compact
    WHERE compact.vehicle_observation_id = forecast.vehicle_observation_id
      AND compact.target_stop_order = forecast.target_stop_order
)
AND NOT EXISTS (
    SELECT 1 FROM evaluation_archive_member archived
    WHERE archived.vehicle_observation_id = forecast.vehicle_observation_id
      AND archived.target_stop_order = forecast.target_stop_order
);

CREATE OR REPLACE VIEW forecast_evaluation AS
SELECT * FROM forecast_evaluation_result
UNION ALL
SELECT compact.vehicle_observation_id, compact.target_stop_order, forecast.route_version_id,
       'SETTLED'::varchar(16), compact.arrival_observation_id, compact.seats_on_arrival,
       compact.scored_at, compact.arrived_at, arrival.route_version_id,
       arrival.vehicle_id, arrival.stop_order, arrival.running_state, arrival.remaining_seats,
       arrival.seat_unknown_reason, compact.arrival_vehicle_trip_key, compact.arrival_quality_direction
FROM forecast_evaluation_compact compact
JOIN seat_forecast forecast USING (vehicle_observation_id, target_stop_order)
JOIN vehicle_observation arrival ON arrival.id = compact.arrival_observation_id
UNION ALL
SELECT vehicle_observation_id, target_stop_order, route_version_id, 'PENDING'::varchar(16),
       NULL::bigint, NULL::integer, NULL::timestamptz, NULL::timestamptz,
       NULL::bigint, NULL::varchar(40), NULL::integer, NULL::integer, NULL::integer,
       NULL::varchar(20), NULL::varchar(120), NULL::bigint
FROM forecast_evaluation_pending;

-- 시간대 통계는 한 정류장 앞의 상세 결과만 사용한다. compact 전체를 거치는 뷰를 읽지 않는다.
CREATE VIEW one_stop_quality_eligible_seat_forecast AS
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.route_version_id,
       forecast.stops_to_target, forecast.model_deployment_id, forecast.demand_statistics_revision,
       forecast.seat_full_chance_raw, forecast.seat_full_chance, forecast.expected_seats, forecast.generated_at,
       evaluation.scoring_state, evaluation.arrival_observation_id, evaluation.seats_on_arrival,
       evaluation.scored_at, forecast.quality_revision
FROM seat_forecast forecast
JOIN forecast_evaluation_result evaluation ON evaluation.vehicle_observation_id = forecast.vehicle_observation_id
    AND evaluation.target_stop_order = forecast.target_stop_order
JOIN forecast_eligible_observation source ON source.id = forecast.vehicle_observation_id
LEFT JOIN observation_trip_assignment arrival_assignment ON arrival_assignment.observation_id = evaluation.arrival_observation_id
LEFT JOIN vehicle_one_way_trip arrival_trip ON arrival_trip.id = COALESCE(arrival_assignment.trip_id, evaluation.arrival_vehicle_trip_key)
WHERE forecast.stops_to_target = 1
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
