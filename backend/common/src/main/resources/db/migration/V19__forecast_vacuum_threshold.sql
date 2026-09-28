-- 큰 예보 표의 행 수 비율만으로 vacuum 시점이 계속 늦어지는 것을 막는다.
-- 검사 주기/worker 수/메모리/비용 제한은 바꾸지 않는다. 즉시 실행이나 완료 시각을 보장하지 않는다.
-- 운영 적용 전후 dead tuple 증가율, vacuum 소요와 I/O를 비교한다.
SET LOCAL lock_timeout = '1s';
ALTER TABLE seat_forecast SET (
    autovacuum_vacuum_scale_factor = 0.01,
    autovacuum_vacuum_threshold = 50,
    autovacuum_vacuum_max_threshold = 10000
);
