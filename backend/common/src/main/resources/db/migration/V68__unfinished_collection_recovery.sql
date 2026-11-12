-- V66 전환 절차와 같이 구 Worker를 중지한 상태에서 적용한다.
-- 미완료만 인덱싱해 수집 회차 시작 전 정리가 전체 수집 이력을 읽지 않게 한다.
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='15s';
CREATE INDEX ix_batch_unfinished_recovery ON observation_batch(scheduled_at,id)
    WHERE outcome IN ('RESERVED','DISPATCHING');
