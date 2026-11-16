-- SAL-434 이전 배포본의 예보·정산·평가·보관이 쓰던 테이블 25개와 뷰 9개를 지운다. 자동 실행하지 않는다. 지운 테이블은 되돌릴 수 없다.
--
-- 대상: 이 테이블들에 줄이 있는 DB. 줄이 없는 DB는 마이그레이션(V83)이 같은 테이블과 뷰를 지운다. 이 파일로 지운 DB와 V83이 지운
--       DB는 같은 스키마가 된다(TransitionSqlTest가 대조한다).
-- 언제: RUNBOOK 「SAL-434 전환 절차」의 차례대로. sal434-02-drop-legacy-triggers.sql을 실행했고, 이관 앱의 옛 테이블 내보내기
--       (ARCHIVE_LEGACY_EXPORT_ENABLED)가 이 테이블들을 기본 키 순서의 조각으로 S3에 내보내고 다시 읽어 확인한 뒤(daily_export 장부,
--       source_date 2000-01-01, 조각마다 한 줄. 테이블마다 로그 status=COMPLETE), sal434-01-precheck.sql로 객체 목록과 장부를
--       다시 뽑아 보고 승인을 받아 실행한다. 쓰기가 뜸한 시간에 한다.
-- 되돌리기: 없다. 줄은 S3 내보내기에서만 되살릴 수 있다. 그래서 (라)가 장부와 줄 수를 맞춰 본다. 이 뒤로는 이전 배포본으로
--   돌아갈 수 없다. 이전 배포본의 api는 이 테이블을 매핑하고 있어 기동하지 못하고, worker는 이 테이블을 쓰는 질의가 모두 실패한다.
-- 먼저 끝나 있어야 하는 것:
--   - SAL-434 배포본이 api·worker에 올라가 있다. 이 배포본은 이 테이블과 뷰를 읽거나 쓰지 않고 매핑한 엔티티도 없어서,
--     지운 뒤에도 그대로 돌고 다시 뜬다. 이 SQL은 배포본을 볼 수 없다.
--   - 운영 서버에 예전 전환 때 설치한 기동 전 검사를 걷었다. /etc/salmonbus/evaluation-work-transition.sh와, api·worker 유닛의
--     드롭인 evaluation-work-transition.conf다. 이 검사는 옛 대기 테이블과 뷰가 있어야 통과하므로, 남겨 둔 채 이 파일을 실행하면
--     그 뒤로 api·worker가 다시 뜨지 못한다. 지금 배포본에는 이 검사가 없다. 이 SQL은 서버의 파일을 볼 수 없다.
--   - 이전 배포본의 이관 앱(노선·날짜 단위로 줄을 S3에 옮기고 지운다)이 멈춰 있다. 배포 파이프라인 밖이라 사람이 지금 배포본의
--     이관 앱으로 바꿀 때까지 돈다. 이 테이블들을 읽고 지우므로 옛 테이블 내보내기를 켜기 전에 멈춘다. 최근 작업은 (다)가 본다.
--   - 옛 테이블 내보내기를 껐다(ARCHIVE_LEGACY_EXPORT_ENABLED=false로 이관 앱 재시작). 켜 둔 채면 끝난 뒤에도 회차마다 테이블의
--     마지막 키 뒤를 읽어서 (다)의 읽기 관찰이 멈춘다.
--   - sal434-02 실행 완료(옛 정산 트리거·함수 없음). 아래 가드가 확인한다. 트리거가 남아 있으면 수집이 배치를 갱신할 때마다
--     트리거가 이 테이블을 읽어 (다)의 읽기 관찰도 통과하지 못한다.
-- 지우는 것(삭제 순서):
--   뷰      alighting_evaluation_status, alighting_quality_eligible_metric, alighting_quality_eligible_evaluation,
--           alighting_forecast_evaluation_metric, training_eligible_seat_forecast, one_stop_operational_seat_forecast,
--           forecast_evaluation, forecast_evaluation_pending, training_eligible_stop_demand_statistics
--   정산    alighting_settlement_pass, _observation, _projection, _batch_work, _generation, _queue_scan
--   평가·보관 alighting_forecast_evaluation_result, alighting_evaluation_archive, alighting_forecast_evaluation_work,
--           forecast_evaluation_result, forecast_evaluation_compact, forecast_evaluation_work, archive_legacy_member,
--           archive_legacy_batch, alighting_history_checkpoint
--   예보    seat_forecast, forecast_publication
--   모델 통계 stop_demand_statistics, file_statistics_publication, demand_statistics_version,
--           model_training_exclusion, statistics_training_exclusion
--   줄 단위 이관 장부 operational_archive_file, operational_archive_job, operational_archive_boundary
-- 지우지 않는 것: observation_batch, vehicle_observation, 뷰 operational_observation(API가 읽는다), 노선·정류장·한도·
--   모델 배포·차량 정원·회원 등 다른 영역, 예보 기록 테이블(forecast_run 등)과 장부(daily_export), 운영에만 있는 historical_import_*·
--   migration_source_record(그 FK는 drop-historical-import-batch-fks.sql이 다룬다). 아래 가드가 이 목록과 겹치지 않음을 확인한다.
-- 가드(하나라도 맞지 않으면 아무것도 바꾸지 않고 멈춘다):
--   (가) 승인값: 같은 세션에서 SET sal434.confirm_drop = '<오늘 한국 날짜 YYYY-MM-DD>'; 가 있어야 한다.
--   (나) 삭제 대상이 아닌 객체가 삭제 대상을 가리키면 그 목록을 보이고 멈춘다: FK, 뷰·구체화 뷰, 본문에 대상 이름이 있는
--        사용자 함수, 발행(publication). 대상끼리의 FK만 카탈로그에서 찾아 먼저 지운다. CASCADE는 쓰지 않는다.
--   (다) 최근 쓰기·읽기: sal434.quiet_minutes(기본 60분) 안에 옛 정산(근거 저장·배치 임대), 옛 예보 발행, 이전 배포본 이관 앱의
--        작업, 옛 평가 보관 기록의 변경이 있거나, sal434.observe_seconds(기본 120초) 동안 대상 테이블의 읽기(seq_scan·idx_scan)나
--        쓰기(n_tup_ins·upd·del) 통계가 늘면 멈춘다. 대시보드나 수동 조회, 켜 둔 옛 테이블 내보내기, 이 관찰과 겹쳐 실행한
--        sal434-01-precheck.sql이 읽어도 멈춘다.
--        위 흔적이 옛 테이블 내보내기가 첫 페이지를 읽기 시작한 시각(daily_export.read_started_at 가운데 가장 이른 값)보다
--        늦어도 멈춘다. 읽은 뒤에 바뀐 값은 조각에 없을 수 있고, 줄 수만으로는 값이 바뀐 줄을 가리지 못하기 때문이다. 조각을 닫은
--        시각(exported_at)과 견주면 첫 페이지를 읽고 조각을 닫기 전에 바뀐 값을 놓친다. 손으로 고친 값은 흔적이 남지 않으니 고친
--        사람이 다시 내보낸다.
--   (라) 내보내기 장부: 줄이 있는 테이블마다 daily_export에서 table_name이 그 테이블이고 verified_at이 있는 줄의
--        row_count 합이 지금 정확한 줄 수와 같아야 한다. 또 그 테이블의 옛 테이블 조각(source_date 2000-01-01)은 번호가
--        0부터 빈틈없이 이어져야 한다. 이관 앱의 옛 테이블 내보내기(LegacyExportService)가 이 계약대로 조각마다 한 줄을 남긴다.
--        모든 쓰기를 막은 뒤(SHARE 잠금) 센다.
-- 지우기 전에 테이블마다 정확한 줄 수와 크기를 NOTICE로 남기고, 지운 뒤 장부의 해당 줄에 dropped_at을 찍는다.
-- 잡는 잠금: (다)의 관찰 동안은 잠금이 없다. 그 뒤 대상 테이블 전체에 SHARE(읽기만 허용)를 잡고 테이블마다 처음부터 끝까지 읽어
--   줄을 센다. 디스크를 많이 읽으므로 읽기 여유(RDS는 EBS 처리량 크레딧)가 충분한 때 한다. 세는 동안 대상 테이블에 쓰는 것은
--   없어야 하고 읽기는 계속된다. 마지막에 DROP이 ACCESS EXCLUSIVE를 잡는다(lock_timeout 5초). statement_timeout 120분.
-- 여러 번 실행해도 된다. 대상이 이미 없으면 알리고 끝난다.
-- Flyway: 이 SQL 뒤에도 validate·migrate는 통과한다. Flyway는 적용 이력과 파일 체크섬만 보고 테이블 존재는 보지 않는다
--   (TransitionSqlTest로 확인).
-- 실행: psql -X -v ON_ERROR_STOP=1 -c "SET sal434.confirm_drop = '...'" -f sal434-03-drop-legacy-tables.sql
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120min';
SET LOCAL search_path = public;

DO $$
DECLARE
    today constant date := (now() AT TIME ZONE 'Asia/Seoul')::date;
    confirm text := nullif(current_setting('sal434.confirm_drop', true), '');
    quiet interval := make_interval(mins => coalesce(nullif(current_setting('sal434.quiet_minutes', true), '')::int, 60));
    observe_seconds int := coalesce(nullif(current_setting('sal434.observe_seconds', true), '')::int, 120);
    target_views constant text[] := ARRAY['alighting_evaluation_status', 'alighting_quality_eligible_metric',
        'alighting_quality_eligible_evaluation', 'alighting_forecast_evaluation_metric', 'training_eligible_seat_forecast',
        'one_stop_operational_seat_forecast', 'forecast_evaluation', 'forecast_evaluation_pending',
        'training_eligible_stop_demand_statistics'];
    target_tables constant text[] := ARRAY['alighting_settlement_pass', 'alighting_settlement_observation',
        'alighting_settlement_projection', 'alighting_settlement_batch_work', 'alighting_settlement_generation',
        'alighting_settlement_queue_scan', 'alighting_forecast_evaluation_result', 'alighting_evaluation_archive',
        'alighting_forecast_evaluation_work', 'forecast_evaluation_result', 'forecast_evaluation_compact',
        'forecast_evaluation_work', 'archive_legacy_member', 'archive_legacy_batch', 'alighting_history_checkpoint',
        'seat_forecast', 'forecast_publication', 'stop_demand_statistics', 'file_statistics_publication',
        'demand_statistics_version', 'model_training_exclusion', 'statistics_training_exclusion',
        'operational_archive_file', 'operational_archive_job', 'operational_archive_boundary'];
    protected constant text[] := ARRAY['observation_batch', 'vehicle_observation', 'operational_observation', 'route',
        'route_version', 'route_stop', 'daily_call_quota', 'model_deployment', 'vehicle_capacity',
        'vehicle_capacity_history', 'member', 'forecast_run', 'forecast_record', 'stop_pass', 'pass_label',
        'forecast_unresolved', 'daily_export', 'historical_import_record', 'migration_source_record'];
    existing_views text[];
    existing_tables text[];
    targets oid[];
    problems text;
    latest timestamptz;
    newest timestamptz;
    first_read timestamptz;
    before jsonb;
    after jsonb;
    t text;
    r record;
    rows_now bigint;
    ledger_rows bigint;
    segment_count bigint;
    last_segment integer;
    mismatches text := '';
BEGIN
    IF target_tables && protected OR target_views && protected THEN
        RAISE EXCEPTION 'SAL-434: 삭제 대상과 보호 목록이 겹친다. 이 파일이 잘못 고쳐졌다';
    END IF;

    existing_views := ARRAY(SELECT name FROM unnest(target_views) WITH ORDINALITY AS x(name, ord)
                             WHERE to_regclass('public.' || name) IS NOT NULL ORDER BY ord);
    existing_tables := ARRAY(SELECT name FROM unnest(target_tables) WITH ORDINALITY AS x(name, ord)
                              WHERE to_regclass('public.' || name) IS NOT NULL ORDER BY ord);
    IF cardinality(existing_views) = 0 AND cardinality(existing_tables) = 0 THEN
        RAISE NOTICE 'SAL-434: 삭제 대상이 이미 없다. 바꾸는 것 없이 끝난다';
        RETURN;
    END IF;
    IF cardinality(existing_tables) < cardinality(target_tables) OR cardinality(existing_views) < cardinality(target_views) THEN
        RAISE NOTICE 'SAL-434: 대상 일부가 이미 없다. 남은 것만 다룬다: 테이블 % 뷰 %', existing_tables, existing_views;
    END IF;

    -- (가) 승인값.
    IF confirm IS DISTINCT FROM to_char(today, 'YYYY-MM-DD') THEN
        RAISE EXCEPTION 'SAL-434: 승인값이 없다. SET sal434.confirm_drop = ''%''; 를 넣고 다시 실행한다', to_char(today, 'YYYY-MM-DD');
    END IF;
    IF quiet < interval '1 minute' OR observe_seconds < 1 THEN
        RAISE EXCEPTION 'SAL-434: sal434.quiet_minutes는 1 이상, sal434.observe_seconds는 1 이상이어야 한다';
    END IF;

    -- 02가 먼저다. 트리거 함수는 이 테이블들을 이름으로 쓰고, 남아 있으면 수집이 배치를 갱신할 때마다 이 테이블들을 읽는다.
    SELECT string_agg(name, ', ') INTO problems
      FROM (SELECT tgname::text AS name FROM pg_trigger WHERE NOT tgisinternal
              AND tgname IN ('trg_alighting_evaluation_work', 'trg_alighting_settlement_batch',
                  'trg_alighting_settlement_batch_cleanup', 'trg_alighting_batch_insert', 'trg_alighting_batch_update',
                  'trg_alighting_batch_delete', 'trg_alighting_observation_insert', 'trg_alighting_observation_update',
                  'trg_alighting_observation_delete')
            UNION ALL
            SELECT proname::text FROM pg_proc WHERE pronamespace = 'public'::regnamespace
              AND proname IN ('register_alighting_evaluation_work', 'register_alighting_settlement_batch',
                  'cleanup_alighting_settlement_batch', 'track_alighting_batch_changes',
                  'track_alighting_observation_changes', 'invalidate_alighting_settlement',
                  'enqueue_alighting_settlement_batch')) x;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 옛 정산 트리거·함수가 남아 있다(sal434-02 먼저): %', problems;
    END IF;

    targets := ARRAY(SELECT to_regclass('public.' || name)::oid FROM unnest(existing_tables || existing_views) AS name);

    -- (나) 대상 밖에서 대상을 가리키는 것.
    SELECT string_agg(conrelid::regclass || '.' || conname || ' -> ' || confrelid::regclass, ', ') INTO problems
      FROM pg_constraint WHERE contype = 'f' AND confrelid = ANY (targets) AND NOT conrelid = ANY (targets);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 삭제 대상이 아닌 테이블이 삭제 대상을 FK로 가리킨다: %', problems;
    END IF;
    SELECT string_agg(DISTINCT v.oid::regclass || ' -> ' || dep.refobjid::regclass, ', ') INTO problems
      FROM pg_depend dep JOIN pg_rewrite rw ON rw.oid = dep.objid JOIN pg_class v ON v.oid = rw.ev_class
     WHERE dep.classid = 'pg_rewrite'::regclass AND dep.refclassid = 'pg_class'::regclass
       AND dep.refobjid = ANY (targets) AND NOT v.oid = ANY (targets);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 삭제 대상이 아닌 뷰가 삭제 대상을 읽는다: %', problems;
    END IF;
    SELECT string_agg(DISTINCT p.oid::regprocedure::text, ', ') INTO problems
      FROM pg_proc p
     WHERE p.pronamespace NOT IN ('pg_catalog'::regnamespace, 'information_schema'::regnamespace)
       AND p.pronamespace <> pg_my_temp_schema()
       AND NOT EXISTS (SELECT 1 FROM pg_depend e WHERE e.classid = 'pg_proc'::regclass AND e.objid = p.oid AND e.deptype = 'e')
       AND (p.prosrc ~* ('\m(' || array_to_string(existing_tables || existing_views, '|') || ')\M')
            OR EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid
                       AND d.refclassid = 'pg_class'::regclass AND d.refobjid = ANY (targets)));
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 본문이 삭제 대상을 쓰는 함수가 있다: %', problems;
    END IF;
    SELECT string_agg(prpubid::text || ':' || prrelid::regclass, ', ') INTO problems
      FROM pg_publication_rel WHERE prrelid = ANY (targets);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 삭제 대상이 발행(publication)에 들어 있다: %', problems;
    END IF;

    -- (다) 최근 흔적.
    IF to_regclass('public.alighting_settlement_projection') IS NOT NULL THEN
        SELECT max(updated_at) INTO latest FROM alighting_settlement_projection;
        newest := greatest(newest, latest);
        IF latest > now() - quiet THEN
            RAISE EXCEPTION 'SAL-434: 옛 정산이 % 에 근거를 저장했다. SAL-434 배포본에는 옛 정산이 없다. 이전 배포본으로 되돌린 worker가 돌고 있지 않은지 확인하고, 마지막 흔적에서 % 이 지난 뒤 다시 실행한다', latest, quiet;
        END IF;
    END IF;
    IF to_regclass('public.alighting_settlement_batch_work') IS NOT NULL THEN
        SELECT max(lease_until) INTO latest FROM alighting_settlement_batch_work;
        newest := greatest(newest, latest);
        IF latest > now() - quiet THEN
            RAISE EXCEPTION 'SAL-434: 옛 정산이 배치를 잡았다(임대 만료 %). SAL-434 배포본에는 옛 정산이 없다. 이전 배포본으로 되돌린 worker가 돌고 있지 않은지 확인하고, 임대 만료에서 % 이 지난 뒤 다시 실행한다', latest, quiet;
        END IF;
    END IF;
    IF to_regclass('public.forecast_publication') IS NOT NULL THEN
        SELECT max(p.published_at) INTO latest
          FROM route_version v
          CROSS JOIN LATERAL (SELECT published_at FROM forecast_publication f WHERE f.route_version_id = v.id
                               ORDER BY f.observed_at DESC, f.source_batch_id DESC LIMIT 1) p;
        newest := greatest(newest, latest);
        IF latest > now() - quiet THEN
            RAISE EXCEPTION 'SAL-434: 옛 예보가 % 에 발행했다. SAL-434 배포본이 worker에 올라가 있는지, 이전 배포본으로 되돌린 worker가 돌고 있지 않은지 확인하고, 마지막 발행에서 % 이 지난 뒤 다시 실행한다', latest, quiet;
        END IF;
    END IF;
    IF to_regclass('public.operational_archive_job') IS NOT NULL THEN
        SELECT max(greatest(last_attempt_at, created_at, completed_at)) INTO latest FROM operational_archive_job;
        newest := greatest(newest, latest);
        IF latest > now() - quiet THEN
            RAISE EXCEPTION 'SAL-434: 이전 배포본의 이관 앱(줄 단위 이관)이 % 에 작업했다. 그 이관 앱이 아직 돌고 있지 않은지 확인하고, 마지막 작업에서 % 이 지난 뒤 다시 실행한다', latest, quiet;
        END IF;
    END IF;
    IF to_regclass('public.archive_legacy_batch') IS NOT NULL THEN
        SELECT max(greatest(lease_until, created_at, verified_at, purged_at, restored_at, abandoned_at)) INTO latest
          FROM archive_legacy_batch;
        newest := greatest(newest, latest);
        IF latest > now() - quiet THEN
            RAISE EXCEPTION 'SAL-434: 옛 평가 보관 기록(archive_legacy_batch)이 % 에 바뀌었다. SAL-434 배포본은 이 테이블을 쓰지 않는다. 쓴 곳을 찾아 멈추고, 그때부터 % 이 지난 뒤 다시 실행한다', latest, quiet;
        END IF;
    END IF;

    -- (다) 내보내기가 읽기 시작한 뒤의 흔적. 읽은 뒤에 바뀐 값은 조각에 없을 수 있다. 줄 수가 같아도 값이 다를 수 있으므로 지우지 않는다.
    SELECT min(read_started_at) INTO first_read FROM daily_export
     WHERE source_date = DATE '2000-01-01' AND table_name = ANY (existing_tables);
    IF newest > first_read THEN
        RAISE EXCEPTION 'SAL-434: 옛 테이블 내보내기가 첫 페이지를 읽기 시작한 시각(%) 뒤에 이전 배포본이 옛 테이블을 썼다(마지막 흔적 %). 읽은 뒤에 바뀐 값이 조각에 없을 수 있어 지우지 않는다. 이전 배포본을 멈춘 뒤 backend/deploy/RUNBOOK.md 「옛 테이블 내보내기」의 방법으로 처음부터 다시 내보낸다.', first_read, newest;
    END IF;

    -- (다) 읽기·쓰기 통계 관찰. 다른 세션의 통계는 트랜잭션이 끝난 뒤 반영된다. 쉬는 세션의 것은 10초쯤 걸릴 수 있다.
    SELECT jsonb_object_agg(relname, jsonb_build_array(seq_scan + coalesce(idx_scan, 0), n_tup_ins + n_tup_upd + n_tup_del))
      INTO before FROM pg_stat_user_tables WHERE relid = ANY (targets);
    PERFORM pg_sleep(observe_seconds);
    PERFORM pg_stat_clear_snapshot();
    SELECT jsonb_object_agg(relname, jsonb_build_array(seq_scan + coalesce(idx_scan, 0), n_tup_ins + n_tup_upd + n_tup_del))
      INTO after FROM pg_stat_user_tables WHERE relid = ANY (targets);
    SELECT string_agg(key || ' 읽기 +' || ((after -> key ->> 0)::bigint - (before -> key ->> 0)::bigint)
                          || ' 쓰기 +' || ((after -> key ->> 1)::bigint - (before -> key ->> 1)::bigint), ', ')
      INTO problems
      FROM jsonb_object_keys(after) AS key
     WHERE after -> key IS DISTINCT FROM before -> key;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: % 초 동안 삭제 대상을 읽거나 쓴 곳이 있다: %. 옛 테이블 내보내기를 껐는지(ARCHIVE_LEGACY_EXPORT_ENABLED), 이전 배포본의 이관 앱이나 worker가 돌고 있지 않은지, 대시보드·수동 조회·sal434-01-precheck.sql이 겹치지 않았는지 확인한다', observe_seconds, problems;
    END IF;

    -- 쓰기를 막고 센다. 읽기는 계속된다.
    EXECUTE 'LOCK TABLE ' || (SELECT string_agg(quote_ident(name), ', ') FROM unnest(existing_tables) AS name) || ' IN SHARE MODE';

    -- (라) 줄 수와 내보내기 장부.
    FOREACH t IN ARRAY existing_tables LOOP
        EXECUTE format('SELECT count(*) FROM %I', t) INTO rows_now;
        SELECT coalesce(sum(row_count), 0) INTO ledger_rows FROM daily_export
         WHERE table_name = t AND verified_at IS NOT NULL;
        SELECT count(*), coalesce(max(segment), -1) INTO segment_count, last_segment FROM daily_export
         WHERE table_name = t AND source_date = DATE '2000-01-01' AND verified_at IS NOT NULL;
        RAISE NOTICE 'SAL-434: % 줄 % 크기 % 장부(검증됨) % 조각 %', t, rows_now, pg_size_pretty(pg_total_relation_size(t::regclass)), ledger_rows, segment_count;
        IF rows_now > 0 AND rows_now <> ledger_rows THEN
            mismatches := mismatches || format('%s(줄 %s, 장부 %s) ', t, rows_now, ledger_rows);
        ELSIF rows_now > 0 AND segment_count <> last_segment + 1 THEN
            mismatches := mismatches || format('%s(조각 %s개인데 마지막 번호 %s) ', t, segment_count, last_segment);
        END IF;
    END LOOP;
    IF mismatches <> '' THEN
        RAISE EXCEPTION 'SAL-434: S3에 검증된 내보내기가 줄 수와 맞지 않는다. 지우지 않는다: %. 옛 테이블 내보내기가 테이블마다 status=COMPLETE로 끝났는지, 그 뒤에 이 테이블에 줄을 쓰거나 지운 곳이 없는지 확인한다', rtrim(mismatches);
    END IF;

    -- 지운다. 뷰 → 대상끼리의 FK → 테이블. CASCADE는 쓰지 않으므로 모르는 의존이 있으면 여기서 실패하고 전부 되돌린다.
    FOREACH t IN ARRAY existing_views LOOP
        EXECUTE format('DROP VIEW %I', t);
    END LOOP;
    FOR r IN SELECT conrelid::regclass AS relation, conname FROM pg_constraint
              WHERE contype = 'f' AND conrelid = ANY (targets) AND confrelid = ANY (targets) LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.relation, r.conname);
    END LOOP;
    FOREACH t IN ARRAY existing_tables LOOP
        EXECUTE format('DROP TABLE %I', t);
        RAISE NOTICE 'SAL-434: % 를 지웠다', t;
    END LOOP;

    UPDATE daily_export SET dropped_at = clock_timestamp()
     WHERE table_name = ANY (existing_tables) AND verified_at IS NOT NULL AND dropped_at IS NULL;

    SELECT string_agg(name, ', ') INTO problems
      FROM unnest(target_tables || target_views) AS name WHERE to_regclass('public.' || name) IS NOT NULL;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 지운 뒤에도 남아 있다: %', problems;
    END IF;
    SELECT string_agg(name, ', ') INTO problems
      FROM unnest(protected[1:17]) AS name WHERE to_regclass('public.' || name) IS NULL;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 지우면 안 되는 객체가 없다: %', problems;
    END IF;
END $$;
COMMIT;
