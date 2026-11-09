-- API migration과 별개인 운영 준비 SQL이다. 자동 실행하지 않는다.
-- 실제 pg_indexes와 여유 공간/예상 index 크기를 확인한 뒤 필요한 것만 한 개씩 실행한다.
-- CONCURRENTLY도 I/O와 공간을 사용한다. 각 문장은 transaction 밖에서 실행한다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_archive_evaluation_arrival_fk
    ON forecast_evaluation_result(arrival_observation_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_archive_compact_arrival_fk
    ON forecast_evaluation_compact(arrival_observation_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_archive_sample_arrival_fk
    ON stop_demand_pending_sample(arrival_observation_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_archive_forecast_publication_fk
    ON seat_forecast(publication_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_archive_batch_date
    ON observation_batch(route_version_id,(COALESCE(response_received_at,scheduled_at)),id);
