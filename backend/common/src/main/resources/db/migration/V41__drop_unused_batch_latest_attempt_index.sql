-- 최신 성공 관측은 ix_batch_forecast_candidates로 조회한다.
-- scheduled_at 기준 최신 시도 조회는 더 이상 없으므로 쓰기와 저장 비용만 남은 인덱스를 제거한다.
DROP INDEX CONCURRENTLY ix_batch_latest_attempt;
