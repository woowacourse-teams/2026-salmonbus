-- 기점(stop_order 1)에서 이전 편도로 보는 관측을 정류소 도착(운행 상태 1)으로 좁힌다.
-- 교차로 통과(운행 상태 0)는 다른 규칙과 같이 기점을 지난 이번 편도로 본다.
-- 외부 학습 조회의 이름, 컬럼 순서, 타입을 유지한다.
SET LOCAL lock_timeout = '5s';

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
        AND (observation.remaining_seats IS NULL OR observation.remaining_seats <= 70)
        AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild rebuild
            WHERE rebuild.route_version_id = observation.route_version_id AND NOT rebuild.completed
              AND (rebuild.vehicle_id = '' OR rebuild.vehicle_id = observation.vehicle_id))) AS forecast_eligible
FROM vehicle_observation observation
LEFT JOIN observation_trip_assignment assignment ON assignment.observation_id = observation.id
LEFT JOIN vehicle_one_way_trip trip ON trip.id = COALESCE(assignment.trip_id, observation.vehicle_trip_key)
LEFT JOIN route_version version ON version.id = observation.route_version_id;
