-- 과거 자료를 실시간 수집으로 추정하지 않는다. 수집 어댑터가 새 배치에만 LIVE를 기록한다.
-- 운영 DB에 별도로 생성된 같은 이름의 열이 있으면 그 값은 유지한다.
SET LOCAL lock_timeout = '5s';
ALTER TABLE observation_batch
    ADD COLUMN IF NOT EXISTS ingestion_origin varchar(40) NOT NULL DEFAULT 'UNVERIFIED';
ALTER TABLE observation_batch ALTER COLUMN ingestion_origin SET DEFAULT 'UNVERIFIED';
COMMENT ON COLUMN observation_batch.ingestion_origin IS
    'LIVE: realtime collector; UNVERIFIED: provenance unavailable. Unknown imports are excluded from confirmed-time models.';
