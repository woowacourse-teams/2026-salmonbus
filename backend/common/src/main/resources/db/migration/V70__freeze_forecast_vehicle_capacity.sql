-- 정원 정책 전환 전에 발행한 예보의 미처리는 새 정답 계약으로 재해석하지 않는다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
INSERT INTO alighting_forecast_evaluation_result (
    vehicle_observation_id,target_stop_order,target_kind,label_version,route_version_id,
    scoring_state,reason,quality_revision,scored_at
)
SELECT vehicle_observation_id,target_stop_order,target_kind,label_version,route_version_id,
       'UNSUPPORTED','MODEL_CONTRACT_RETIRED',1,CURRENT_TIMESTAMP
FROM alighting_forecast_evaluation_work work
WHERE NOT EXISTS (SELECT 1 FROM alighting_forecast_evaluation_result result
    WHERE (result.vehicle_observation_id,result.target_stop_order,result.target_kind,result.label_version)=
          (work.vehicle_observation_id,work.target_stop_order,work.target_kind,work.label_version))
  AND label_version NOT IN ('alighting-confirmed-capacity-snapshot-v3','alighting-capacity-snapshot-v2')
ON CONFLICT DO NOTHING;
DELETE FROM alighting_forecast_evaluation_work
WHERE label_version NOT IN ('alighting-confirmed-capacity-snapshot-v3','alighting-capacity-snapshot-v2');

-- 과거 결과의 근거 ID와 정원은 보존하되, 정원 근거 관측이 원본 이관을 막지 않게 한다.
DO $$ DECLARE constraint_name text; BEGIN
    FOR constraint_name IN
        SELECT conname FROM pg_constraint
        WHERE conrelid='alighting_forecast_evaluation_result'::regclass AND contype='f'
          AND conkey=ARRAY[(SELECT attnum FROM pg_attribute
              WHERE attrelid='alighting_forecast_evaluation_result'::regclass
                AND attname='capacity_observation_id')]::smallint[]
    LOOP
        EXECUTE format('ALTER TABLE alighting_forecast_evaluation_result DROP CONSTRAINT %I',constraint_name);
    END LOOP;
END $$;
