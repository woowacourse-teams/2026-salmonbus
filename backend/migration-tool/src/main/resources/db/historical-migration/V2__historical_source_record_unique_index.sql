-- observation_batch는 live worker가 계속 쓰므로 테이블 write lock을 오래 잡지 않는다.
-- 기존 live 행은 semantic_batch_digest가 NULL이라 partial index에는 들어가지 않는다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_observation_batch_historical_semantic
    ON observation_batch (semantic_batch_digest)
    WHERE semantic_batch_digest IS NOT NULL;
