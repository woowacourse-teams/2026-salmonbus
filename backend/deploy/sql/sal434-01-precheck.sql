-- SAL-434 전환 사전 확인. 읽기 전용이다. 자동 실행하지 않는다.
--
-- 언제: RUNBOOK "SAL-434 전환 절차"의 5·7·8번 차례(sal434-02, 03, 04a/04 실행) 직전에 각각 한 번, 그리고 실행 직후 한 번.
--       사람이 이 출력을 보고 다음 단계를 승인한다. 결과는 날짜와 함께 보관한다.
-- 하는 일: 전환이 건드릴 객체를 전부 보여 준다. 아무것도 바꾸지 않는다(READ ONLY 트랜잭션).
--   1. 환경(PostgreSQL 버전, Flyway 최대 버전, 실행 계정, 한국 시각)
--   2. 삭제 대상 테이블·뷰(sal434-03의 목록)의 존재·크기·예상 줄 수·누적 읽기/쓰기 통계·마지막 VACUUM/ANALYZE.
--      PostgreSQL은 테이블별 "마지막 쓰기 시각"을 남기지 않는다. 누적 통계(seq_scan, idx_scan, n_tup_ins/upd/del)를
--      captured_at과 함께 보여 주므로, 몇 분 뒤 한 번 더 실행해 값이 늘었는지 비교한다. 늘었으면 아직 누가 쓰거나 읽는다.
--   3. 옛 경로가 아직 쓰는지 판단할 시각(옛 정산 근거 저장, 배치 임대, 옛 예보 발행, 옛 이관 작업, 새 채점 통과)
--   4. 옛 정산 트리거·함수(sal434-02 대상)의 존재
--   5. 삭제 대상·관측 두 테이블을 가리키는 FK, 뷰, 함수. 마이그레이션(V1~V83)에 없는 테이블에서 온 것은 따로 표시한다
--   6. 마이그레이션에 없는 테이블·뷰와 관측·예보 테이블의 인덱스 가운데 마이그레이션에 없는 것
--   7. 새 테이블의 파티션 목록, 크기, 예상 줄 수. 기본 파티션은 정확히 센다(날짜 파티션이 없어 넘친 줄)
--   8. 내보내기 장부(daily_export) 상태. 삭제 대상 테이블마다 실제 줄 수, sal434-03이 요구하는 "검증된 장부 줄 수 합",
--      옛 테이블 조각 수와 번호 연속 여부, 마지막 조각 시각, 03의 (라) 판정을 보인다. 실제 줄 수는 64MB 이하 테이블만 센다.
--      전부 세려면 같은 세션에서 SET sal434.count_legacy_rows = 'exact'; 를 먼저 넣는다. 이때는 옛 테이블 전체(운영 DB
--      용량의 대부분)를 읽어 수십 분이 걸리고 EBS 크레딧을 크게 쓰므로(추정) 문장 제한 시간이 120분으로 늘어난다.
--   9. 관측 두 테이블의 파티션 전환 준비 상태(sal434-04a·04)
--  10. 지금 붙어 있는 연결(application_name별)
-- 잡는 잠금과 예상 시간: 읽는 테이블마다 ACCESS SHARE. 큰 테이블은 세지 않고 카탈로그 통계만 읽는다. 기본 파티션과
--   작은 장부, 64MB 이하 옛 테이블만 센다. 로컬에서 1초 미만, 운영도 수 초로 본다(추정). statement_timeout 60초
--   (sal434.count_legacy_rows = 'exact'이면 120분), lock_timeout 3초.
-- 되돌리기: 바꾸는 것이 없다.
-- 실행: psql -X -v ON_ERROR_STOP=1 -P pager=off -f sal434-01-precheck.sql > precheck-$(date +%Y%m%d-%H%M).txt
--   옛 테이블 줄을 모두 셀 때: psql -X -v ON_ERROR_STOP=1 -P pager=off -c "SET sal434.count_legacy_rows = 'exact'" -f sal434-01-precheck.sql
BEGIN READ ONLY;
SELECT set_config('statement_timeout',
    CASE WHEN current_setting('sal434.count_legacy_rows', true) = 'exact' THEN '120min' ELSE '60s' END, true) AS statement_timeout;
SET LOCAL lock_timeout = '3s';
SET LOCAL search_path = public;

-- 1. 환경
SELECT version() AS postgres,
       (SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$') AS flyway_max_version,
       current_user AS executed_by,
       now() AT TIME ZONE 'Asia/Seoul' AS captured_at_kst;

-- 공통 목록: sal434-03의 삭제 대상(뷰 9개 다음에 테이블 25개)과 V1~V83이 만드는 테이블·뷰.
-- READ ONLY 트랜잭션에서는 임시 테이블도 만들 수 없어 세션 설정값에 담는다.
SET LOCAL sal434.precheck_targets = 'alighting_evaluation_status,alighting_quality_eligible_metric,alighting_quality_eligible_evaluation,alighting_forecast_evaluation_metric,training_eligible_seat_forecast,one_stop_operational_seat_forecast,forecast_evaluation,forecast_evaluation_pending,training_eligible_stop_demand_statistics,alighting_settlement_pass,alighting_settlement_observation,alighting_settlement_projection,alighting_settlement_batch_work,alighting_settlement_generation,alighting_settlement_queue_scan,alighting_forecast_evaluation_result,alighting_evaluation_archive,alighting_forecast_evaluation_work,forecast_evaluation_result,forecast_evaluation_compact,forecast_evaluation_work,archive_legacy_member,archive_legacy_batch,alighting_history_checkpoint,seat_forecast,forecast_publication,stop_demand_statistics,file_statistics_publication,demand_statistics_version,model_training_exclusion,statistics_training_exclusion,operational_archive_file,operational_archive_job,operational_archive_boundary';
SET LOCAL sal434.precheck_migration = 'alighting_evaluation_archive,alighting_forecast_evaluation_result,alighting_forecast_evaluation_work,alighting_history_checkpoint,alighting_settlement_batch_work,alighting_settlement_generation,alighting_settlement_observation,alighting_settlement_pass,alighting_settlement_projection,alighting_settlement_queue_scan,analytics_deletion,archive_legacy_batch,archive_legacy_member,arrival_snapshot,bus_station,bus_station_tile,daily_call_quota,daily_export,demand_statistics_version,favorite,favorite_group,file_statistics_publication,forecast_evaluation_compact,forecast_evaluation_result,forecast_evaluation_work,forecast_publication,forecast_record,forecast_run,forecast_unresolved,member,member_identity,model_deployment,model_training_exclusion,notification_delivery,notification_setting,observation_batch,operational_archive_boundary,operational_archive_file,operational_archive_job,pass_label,provider_unlink,push_device,refresh_token,route,route_display_automatic_artifact,route_display_generation_job,route_display_geometry,route_geometry,route_geometry_recovery,route_geometry_source_cache,route_input_manifest,route_source_blob,route_source_manifest,route_source_manifest_receipt,route_source_manifest_reuse,route_source_receipt,route_stop,route_stop_location_history,route_version,seat_forecast,spring_session,spring_session_attributes,station_seed_dataset,statistics_training_exclusion,stop_demand_statistics,stop_pass,stop_walk_dataset,stop_walk_distance,vehicle_capacity,vehicle_capacity_history,vehicle_observation,alighting_evaluation_status,alighting_forecast_evaluation_metric,alighting_quality_eligible_evaluation,alighting_quality_eligible_metric,forecast_evaluation,forecast_evaluation_pending,one_stop_operational_seat_forecast,operational_observation,training_eligible_seat_forecast,training_eligible_stop_demand_statistics,forecast_outcome,flyway_schema_history';

-- 2. 삭제 대상의 크기와 누적 통계. 몇 분 뒤 다시 실행해 reads·writes가 늘었는지 본다.
SELECT t.kind, t.name, c.relkind AS relkind,
       CASE WHEN c.oid IS NULL THEN '(없음)' ELSE pg_size_pretty(pg_total_relation_size(c.oid)) END AS total_size,
       c.reltuples::bigint AS estimated_rows,
       s.seq_scan + coalesce(s.idx_scan, 0) AS reads, s.n_tup_ins AS inserts, s.n_tup_upd AS updates, s.n_tup_del AS deletes,
       s.n_mod_since_analyze, greatest(s.last_vacuum, s.last_autovacuum) AS last_vacuum,
       greatest(s.last_analyze, s.last_autoanalyze) AS last_analyze, now() AS captured_at
  FROM (SELECT name, CASE WHEN ord <= 9 THEN 'view' ELSE 'table' END AS kind, ord FROM unnest(string_to_array(current_setting('sal434.precheck_targets'), ',')) WITH ORDINALITY AS x(name, ord)) t
  LEFT JOIN pg_class c ON c.oid = to_regclass('public.' || t.name)
  LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
 ORDER BY t.ord;

-- 3. 옛 경로가 아직 쓰는지(없는 테이블은 NULL). 시각 인덱스가 있거나 작은 테이블만 본다.
--    테이블이 지워진 뒤에도 이 파일이 돌도록 조회문을 문자열로 두고 테이블이 있을 때만 실행한다.
SELECT 'old_settlement_projection_saved' AS evidence, CASE WHEN to_regclass('public.alighting_settlement_projection') IS NULL THEN NULL
       ELSE (xpath('/row/v/text()', query_to_xml($q$SELECT max(updated_at) AS v FROM alighting_settlement_projection$q$, false, true, '')))[1]::text END AS latest
UNION ALL
SELECT 'old_settlement_batch_leased' AS evidence, CASE WHEN to_regclass('public.alighting_settlement_batch_work') IS NULL THEN NULL
       ELSE (xpath('/row/v/text()', query_to_xml($q$SELECT max(lease_until) AS v FROM alighting_settlement_batch_work$q$, false, true, '')))[1]::text END AS latest
UNION ALL
SELECT 'old_forecast_published' AS evidence, CASE WHEN to_regclass('public.forecast_publication') IS NULL THEN NULL
       ELSE (xpath('/row/v/text()', query_to_xml($q$SELECT max(p.published_at) AS v FROM route_version r CROSS JOIN LATERAL (SELECT published_at FROM forecast_publication f WHERE f.route_version_id = r.id ORDER BY f.observed_at DESC, f.source_batch_id DESC LIMIT 1) p$q$, false, true, '')))[1]::text END AS latest
UNION ALL
SELECT 'old_archive_job' AS evidence, CASE WHEN to_regclass('public.operational_archive_job') IS NULL THEN NULL
       ELSE (xpath('/row/v/text()', query_to_xml($q$SELECT max(greatest(last_attempt_at, created_at, completed_at)) AS v FROM operational_archive_job$q$, false, true, '')))[1]::text END AS latest
UNION ALL
SELECT 'new_forecast_run' AS evidence, CASE WHEN to_regclass('public.forecast_run') IS NULL THEN NULL
       ELSE (xpath('/row/v/text()', query_to_xml($q$SELECT max(x.received_at) AS v FROM route_version r CROSS JOIN LATERAL (SELECT received_at FROM forecast_run f WHERE f.route_version_id = r.id ORDER BY f.received_at DESC, f.observation_batch_id DESC LIMIT 1) x$q$, false, true, '')))[1]::text END AS latest
UNION ALL
SELECT 'new_stop_pass_closed' AS evidence, CASE WHEN to_regclass('public.stop_pass') IS NULL THEN NULL
       ELSE (xpath('/row/v/text()', query_to_xml($q$SELECT max(x.closed_at) AS v FROM route r CROSS JOIN LATERAL (SELECT closed_at FROM stop_pass p WHERE p.route_id = r.id ORDER BY p.closed_at DESC LIMIT 1) x$q$, false, true, '')))[1]::text END AS latest;

-- 4. 옛 정산 트리거와 함수(sal434-02 대상). 02 뒤에는 0줄이어야 한다.
SELECT 'trigger' AS kind, tgrelid::regclass::text AS on_table, tgname::text AS name, tgfoid::regproc::text AS function_name
  FROM pg_trigger WHERE NOT tgisinternal
   AND tgname IN ('trg_alighting_evaluation_work', 'trg_alighting_settlement_batch', 'trg_alighting_settlement_batch_cleanup',
       'trg_alighting_batch_insert', 'trg_alighting_batch_update', 'trg_alighting_batch_delete',
       'trg_alighting_observation_insert', 'trg_alighting_observation_update', 'trg_alighting_observation_delete')
UNION ALL
SELECT 'function', NULL, p.oid::regprocedure::text, NULL FROM pg_proc p
 WHERE p.pronamespace = 'public'::regnamespace
   AND p.proname IN ('register_alighting_evaluation_work', 'register_alighting_settlement_batch',
       'cleanup_alighting_settlement_batch', 'track_alighting_batch_changes', 'track_alighting_observation_changes',
       'invalidate_alighting_settlement', 'enqueue_alighting_settlement_batch')
ORDER BY 1, 2, 3;
-- 전체 사용자 트리거(참고).
SELECT tgrelid::regclass AS on_table, tgname, tgfoid::regproc AS function_name
  FROM pg_trigger WHERE NOT tgisinternal ORDER BY 1, 2;

-- 5-1. 삭제 대상과 관측 두 테이블을 가리키는 FK. referencing_is_target=false인 줄은 sal434-03·04가 멈추는 원인이다.
SELECT con.conrelid::regclass AS referencing_table, con.conname, con.confrelid::regclass AS referenced_table,
       con.conrelid::regclass::text = ANY (string_to_array(current_setting('sal434.precheck_targets'), ',')) AS referencing_is_target,
       con.conrelid::regclass::text = ANY (string_to_array(current_setting('sal434.precheck_migration'), ',')) AS referencing_in_migrations,
       pg_get_constraintdef(con.oid) AS definition
  FROM pg_constraint con
 WHERE con.contype = 'f'
   AND (con.confrelid::regclass::text = ANY (string_to_array(current_setting('sal434.precheck_targets'), ','))
        OR con.confrelid IN (to_regclass('public.observation_batch'), to_regclass('public.vehicle_observation')))
 ORDER BY referencing_is_target, 3, 1, 2;

-- 5-2. 삭제 대상·관측 테이블을 읽는 뷰. 대상 밖의 뷰(dependent_is_target=false)는 operational_observation만 있어야 한다.
SELECT DISTINCT v.oid::regclass AS dependent_view, v.relkind, dep.refobjid::regclass AS reads,
       v.oid::regclass::text = ANY (string_to_array(current_setting('sal434.precheck_targets'), ',')) AS dependent_is_target,
       v.oid::regclass::text = ANY (string_to_array(current_setting('sal434.precheck_migration'), ',')) AS dependent_in_migrations
  FROM pg_depend dep JOIN pg_rewrite rw ON rw.oid = dep.objid JOIN pg_class v ON v.oid = rw.ev_class
 WHERE dep.classid = 'pg_rewrite'::regclass AND dep.refclassid = 'pg_class'::regclass AND v.oid <> dep.refobjid
   AND (dep.refobjid::regclass::text = ANY (string_to_array(current_setting('sal434.precheck_targets'), ','))
        OR dep.refobjid IN (to_regclass('public.observation_batch'), to_regclass('public.vehicle_observation')))
 ORDER BY 4, 1, 3;

-- 5-3. 본문에 삭제 대상 이름이 있는 사용자 함수(확장 함수 제외). sal434-02 뒤에는 0줄이어야 한다.
SELECT p.oid::regprocedure AS function_name,
       array(SELECT t.name FROM (SELECT name, CASE WHEN ord <= 9 THEN 'view' ELSE 'table' END AS kind, ord FROM unnest(string_to_array(current_setting('sal434.precheck_targets'), ',')) WITH ORDINALITY AS x(name, ord)) t WHERE p.prosrc ~* ('\m' || t.name || '\M') ORDER BY t.ord) AS mentions
  FROM pg_proc p
 WHERE p.pronamespace NOT IN ('pg_catalog'::regnamespace, 'information_schema'::regnamespace)
   AND NOT EXISTS (SELECT 1 FROM pg_depend e WHERE e.classid = 'pg_proc'::regclass AND e.objid = p.oid AND e.deptype = 'e')
   AND EXISTS (SELECT 1 FROM (SELECT name, CASE WHEN ord <= 9 THEN 'view' ELSE 'table' END AS kind, ord FROM unnest(string_to_array(current_setting('sal434.precheck_targets'), ',')) WITH ORDINALITY AS x(name, ord)) t WHERE p.prosrc ~* ('\m' || t.name || '\M'))
 ORDER BY 1;

-- 6-1. 마이그레이션(V1~V83)에 없는 테이블·뷰. 운영에는 historical_import_*·migration_source_record 등이 있다.
SELECT c.relname, c.relkind, pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size, c.reltuples::bigint AS estimated_rows
  FROM pg_class c
 WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm') AND NOT c.relispartition
   AND NOT c.relname = ANY (string_to_array(current_setting('sal434.precheck_migration'), ','))
 ORDER BY 1;
-- 6-2. 관측·예보 테이블과 삭제 대상의 인덱스. 운영에만 있는 수동 인덱스(ix_archive_* 등)를 확인한다.
SELECT i.indrelid::regclass AS table_name, c.relname AS index_name, i.indisunique AS is_unique, i.indisvalid AS valid,
       pg_size_pretty(pg_relation_size(c.oid)) AS size, pg_get_indexdef(i.indexrelid) AS definition
  FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_class t ON t.oid = i.indrelid
 WHERE t.relnamespace = 'public'::regnamespace AND NOT t.relispartition
   AND (t.relname IN ('observation_batch', 'vehicle_observation') OR t.relname = ANY (string_to_array(current_setting('sal434.precheck_targets'), ',')))
 ORDER BY 1, 2;

-- 7. 새 테이블의 파티션. 기본 파티션의 줄은 날짜 파티션이 없던 날의 줄이다(정확히 센다).
SELECT parent.relname AS parent, child.relname AS partition, pg_get_expr(child.relpartbound, child.oid) AS bound,
       pg_size_pretty(pg_total_relation_size(child.oid)) AS total_size, child.reltuples::bigint AS estimated_rows
  FROM pg_inherits JOIN pg_class parent ON parent.oid = inhparent JOIN pg_class child ON child.oid = inhrelid
 WHERE parent.relname IN ('forecast_run', 'forecast_record', 'stop_pass', 'pass_label', 'forecast_unresolved',
                          'observation_batch', 'vehicle_observation')
 ORDER BY 1, 2;
SELECT 'forecast_run_default' AS partition, count(*) AS exact_rows FROM forecast_run_default
UNION ALL SELECT 'forecast_record_default', count(*) FROM forecast_record_default
UNION ALL SELECT 'stop_pass_default', count(*) FROM stop_pass_default
UNION ALL SELECT 'pass_label_default', count(*) FROM pass_label_default
UNION ALL SELECT 'forecast_unresolved_default', count(*) FROM forecast_unresolved_default;

-- 8-1. 내보내기 장부(테이블별). 옛 테이블 조각은 source_date 2000-01-01에 조각마다 한 줄이다.
SELECT table_name, count(DISTINCT source_date) AS dates, count(*) AS ledger_lines, min(source_date) AS first_date,
       max(source_date) AS last_date,
       sum(row_count) AS ledger_rows, count(verified_at) AS verified, count(dropped_at) AS dropped,
       sum(row_count) FILTER (WHERE verified_at IS NOT NULL) AS verified_rows, max(exported_at) AS last_exported_at
  FROM daily_export GROUP BY table_name ORDER BY table_name;
-- 8-2. 삭제 대상별로 sal434-03의 (라) 조건을 미리 본다. exact_rows가 비어 있으면 세지 않은 것이다(머리 주석 8).
--      03은 쓰기를 막고 다시 센다. 여기서 ok여도 그 사이에 줄이 바뀌면 03이 멈춘다.
SELECT t.name, CASE WHEN c.oid IS NULL THEN '(없음)' ELSE pg_size_pretty(pg_total_relation_size(c.oid)) END AS total_size,
       c.reltuples::bigint AS estimated_rows, x.exact_rows, l.verified_ledger_rows, l.segments, l.last_segment,
       l.last_segment_exported_at, l.unverified_lines,
       CASE WHEN c.oid IS NULL THEN 'absent'
            WHEN x.exact_rows IS NULL THEN 'not counted'
            WHEN x.exact_rows = 0 THEN 'ok(empty)'
            WHEN x.exact_rows <> l.verified_ledger_rows THEN 'mismatch'
            WHEN l.segments <> l.last_segment + 1 THEN 'segment gap'
            ELSE 'ok' END AS sal434_03_ledger_check
  FROM (SELECT name, CASE WHEN ord <= 9 THEN 'view' ELSE 'table' END AS kind, ord FROM unnest(string_to_array(current_setting('sal434.precheck_targets'), ',')) WITH ORDINALITY AS x(name, ord)) t
  LEFT JOIN pg_class c ON c.oid = to_regclass('public.' || t.name)
  CROSS JOIN LATERAL (
      SELECT coalesce(sum(d.row_count) FILTER (WHERE d.verified_at IS NOT NULL), 0) AS verified_ledger_rows,
             count(*) FILTER (WHERE d.source_date = DATE '2000-01-01' AND d.verified_at IS NOT NULL) AS segments,
             coalesce(max(d.segment) FILTER (WHERE d.source_date = DATE '2000-01-01' AND d.verified_at IS NOT NULL), -1)
                 AS last_segment,
             max(d.exported_at) FILTER (WHERE d.source_date = DATE '2000-01-01') AS last_segment_exported_at,
             count(*) FILTER (WHERE d.verified_at IS NULL) AS unverified_lines
        FROM daily_export d WHERE d.table_name = t.name) l
  CROSS JOIN LATERAL (
      SELECT CASE WHEN c.oid IS NOT NULL AND c.relkind = 'r'
                   AND (current_setting('sal434.count_legacy_rows', true) = 'exact'
                        OR pg_total_relation_size(c.oid) <= 64 * 1024 * 1024)
             THEN (xpath('/row/v/text()', query_to_xml(format('SELECT count(*) AS v FROM %I', t.name), false, true, '')))[1]::text::bigint
             END AS exact_rows) x
 WHERE t.kind = 'table'
 ORDER BY t.ord;

-- 9. 관측 두 테이블의 파티션 전환 준비(sal434-04a 결과와 04의 조건).
SELECT c.relname, c.relkind, c.relispartition,
       EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'source_date' AND NOT a.attisdropped)
           AS has_source_date,
       (SELECT pg_get_expr(d.adbin, d.adrelid) FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
         WHERE a.attrelid = c.oid AND a.attname = 'source_date') AS source_date_default,
       (SELECT convalidated FROM pg_constraint WHERE conrelid = c.oid AND conname = 'ck_' || replace(c.relname, '_legacy', '') || '_legacy_date')
           AS legacy_date_check_validated,
       (SELECT attidentity FROM pg_attribute WHERE attrelid = c.oid AND attname = 'id') AS id_identity,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size, c.reltuples::bigint AS estimated_rows
  FROM pg_class c
 WHERE c.relnamespace = 'public'::regnamespace
   AND c.relname IN ('observation_batch', 'vehicle_observation', 'observation_batch_legacy', 'vehicle_observation_legacy')
 ORDER BY 1;
SELECT name AS prebuilt_index, i.indisvalid AS valid, i.indisready AS ready,
       EXISTS (SELECT 1 FROM pg_constraint WHERE conindid = i.indexrelid) AS backs_constraint
  FROM unnest(ARRAY['ux_observation_batch_id_date_legacy', 'ux_batch_attempt_date_legacy',
       'ux_vehicle_observation_id_date_legacy', 'ux_observation_source_row_date_legacy',
       'ux_observation_vehicle_per_batch_date_legacy']) AS name
  LEFT JOIN pg_index i ON i.indexrelid = to_regclass('public.' || name)
 ORDER BY 1;

-- 10. 지금 붙어 있는 연결. 02·03·04 전에 옛 이관(salmonbus-archive)·정비 연결을 확인한다.
SELECT coalesce(nullif(application_name, ''), '(없음)') AS application_name, state, count(*) AS connections,
       min(backend_start) AS oldest_backend, min(xact_start) AS oldest_transaction
  FROM pg_stat_activity
 WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend'
 GROUP BY 1, 2 ORDER BY 1, 2;
COMMIT;
