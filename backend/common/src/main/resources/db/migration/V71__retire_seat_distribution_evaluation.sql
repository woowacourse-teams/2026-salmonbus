SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
ALTER TABLE forecast_evaluation_result DROP CONSTRAINT ck_evaluation_state;
ALTER TABLE forecast_evaluation_result ADD CONSTRAINT ck_evaluation_state CHECK (
    scoring_state IN ('PENDING','SETTLED','SKIPPED','LOST','SEAT_MISSING','QUALITY_EXCLUDED','MODEL_RETIRED')
) NOT VALID;

-- 구 모델의 미처리는 정답을 추정하지 않고 전환으로 인한 중단 사실을 보존한다.
INSERT INTO forecast_evaluation_result (
    vehicle_observation_id,target_stop_order,route_version_id,scoring_state,scored_at
)
SELECT vehicle_observation_id,target_stop_order,route_version_id,'MODEL_RETIRED',CURRENT_TIMESTAMP
FROM forecast_evaluation_pending
ON CONFLICT(vehicle_observation_id,target_stop_order) DO NOTHING;
DELETE FROM forecast_evaluation_work;
