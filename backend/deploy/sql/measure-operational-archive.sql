-- 변경 전/후 같은 명령으로 실행한다. 원본 전체 COUNT나 원본 삭제는 수행하지 않는다.
-- psql -X -v ON_ERROR_STOP=1 -f measure-operational-archive.sql
\set ON_ERROR_STOP on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '200ms';
SET LOCAL max_parallel_workers_per_gather = 0;

SELECT clock_timestamp() AS captured_at, pg_database_size(current_database()) AS database_bytes,
       pg_postmaster_start_time() AS database_started_at;

-- live/dead 행은 PostgreSQL 통계 추정값이며 물리 파일의 빈 공간을 뜻하지 않는다.
SELECT relname, n_live_tup, n_dead_tup, n_tup_ins, n_tup_del, last_autovacuum,
       pg_total_relation_size(relid) AS total_bytes, pg_indexes_size(relid) AS index_bytes
FROM pg_stat_user_tables
WHERE schemaname = 'public' AND relname IN (
    'vehicle_observation', 'observation_batch', 'forecast_publication', 'seat_forecast',
    'forecast_evaluation_result', 'forecast_evaluation_compact',
    'les_forecast_evaluation_work', 'les_forecast_evaluation_result', 'les_evaluation_archive',
    'evaluation_archive_member', 'archive_legacy_member',
    'operational_archive_job', 'operational_archive_file'
)
ORDER BY total_bytes DESC;

-- 50,001개가 반환되면 전수 결과가 아니다. 정산 예보 수가 아닌 원본 관측별 작업 수다.
WITH bounded AS MATERIALIZED (
    SELECT vehicle_observation_id, route_version_id FROM forecast_evaluation_work LIMIT 50001
)
SELECT work.route_version_id, count(*) AS observation_work_count,
       min(batch.response_received_at) AS oldest_observed_at,
       count(*) FILTER (WHERE batch.response_received_at < now() - interval '2 hours') AS over_two_hours,
       count(*) FILTER (WHERE batch.response_received_at <
           date_trunc('day', now(), 'Asia/Seoul') - interval '1 day') AS older_than_retention
FROM bounded work
JOIN vehicle_observation observation ON observation.id = work.vehicle_observation_id
JOIN observation_batch batch ON batch.id = observation.observation_batch_id
GROUP BY work.route_version_id ORDER BY oldest_observed_at;

-- 새 모델의 정산은 별도 작업 테이블이다. 50,001개면 전수 결과가 아니다.
-- 확인 시각 모델은 input_confirmed_at부터 2시간을 판정하므로 입력 시각을 구분한다.
WITH bounded AS MATERIALIZED (
    SELECT vehicle_observation_id, route_version_id, label_version, next_attempt_at
    FROM les_forecast_evaluation_work LIMIT 50001
), sources AS (
    SELECT work.route_version_id, work.next_attempt_at,
           CASE WHEN work.label_version='les-confirmed-routeband-v2'
               THEN COALESCE(batch.input_confirmed_at,batch.response_received_at)
               ELSE batch.response_received_at END AS source_at
    FROM bounded work
    JOIN vehicle_observation observation ON observation.id=work.vehicle_observation_id
    JOIN observation_batch batch ON batch.id=observation.observation_batch_id
)
SELECT route_version_id, count(*) AS target_work_count, min(source_at) AS oldest_input_at,
       count(*) FILTER (WHERE next_attempt_at<=now()) AS due_now,
       count(*) FILTER (WHERE source_at<now()-interval '2 hours') AS over_two_hours,
       count(*) FILTER (WHERE source_at<
           date_trunc('day',now(),'Asia/Seoul')-interval '1 day') AS older_than_retention
FROM sources GROUP BY route_version_id ORDER BY oldest_input_at;

-- 1,001개가 반환되면 전수 결과가 아니다. 과거 미완료 수집이 이관을 막는지 확인한다.
WITH bounded AS MATERIALIZED (
    SELECT route_version_id, outcome, scheduled_at FROM observation_batch
    WHERE outcome IN ('RESERVED', 'DISPATCHING') LIMIT 1001
)
SELECT route_version_id, outcome, count(*) AS unfinished_collection_count, min(scheduled_at) AS oldest_at
FROM bounded GROUP BY route_version_id, outcome ORDER BY oldest_at;

SELECT to_regclass('public.operational_archive_job') IS NOT NULL AS has_new_archive \gset
\if :has_new_archive
SELECT state, failure_code, count(*) AS jobs, min(source_date) AS oldest_source_date,
       min(last_attempt_at) AS oldest_last_attempt
FROM operational_archive_job WHERE state <> 'DONE'
GROUP BY state, failure_code ORDER BY oldest_source_date;

SELECT count(*) AS completed_jobs_last_day, min(source_date) AS oldest_completed_source_date,
       max(source_date) AS newest_completed_source_date
FROM operational_archive_job WHERE completed_at >= now() - interval '1 day';
\endif
COMMIT;
