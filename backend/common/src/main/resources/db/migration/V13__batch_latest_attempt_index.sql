-- 최신 시도 및 최신 정상 시도 조회의 정렬 순서와 일치시킨다.
CREATE INDEX ix_batch_latest_attempt
    ON observation_batch (route_version_id, scheduled_at DESC, attempt_number DESC, id DESC);
