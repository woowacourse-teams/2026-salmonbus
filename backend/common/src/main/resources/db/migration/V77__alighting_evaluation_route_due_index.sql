-- 노선별 정산 대기 여부와 처리 순서를 같은 인덱스로 조회한다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '90s';

CREATE INDEX ix_alighting_evaluation_route_due
    ON alighting_forecast_evaluation_work (
        route_version_id, next_attempt_at, vehicle_observation_id, target_stop_order, target_kind
    );
