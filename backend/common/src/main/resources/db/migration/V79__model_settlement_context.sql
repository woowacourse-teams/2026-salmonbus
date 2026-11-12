SET LOCAL lock_timeout = '5s';

-- 예보 행의 모델 배포 ID와 노선 판본으로 발행 당시 정산 근거를 찾는다.
ALTER TABLE model_deployment ADD COLUMN settlement_context jsonb NOT NULL DEFAULT '{}'::jsonb;
