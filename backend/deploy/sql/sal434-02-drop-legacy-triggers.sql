-- SAL-434 옛 정산을 위한 DB 트리거 9개와 그 함수 7개를 지운다. 자동 실행하지 않는다. 테이블과 줄은 지우지 않는다.
--
-- 언제: RUNBOOK "SAL-434 전환 절차" 5번 차례. FORECAST_PIPELINE=v2, FORECAST_SCORING=v2로 운영을 관찰한 뒤,
--       옛 정산이 멈춘 것을 확인하고 승인을 받아 실행한다.
-- 지우는 것:
--   트리거  seat_forecast.trg_alighting_evaluation_work (예측 줄마다 옛 정산 대기 줄을 만든다, V55→V69)
--           alighting_forecast_evaluation_work.trg_alighting_settlement_batch, trg_alighting_settlement_batch_cleanup (V80)
--           observation_batch.trg_alighting_batch_insert/update/delete,
--           vehicle_observation.trg_alighting_observation_insert/update/delete (문장 트리거, 전이 테이블, V80)
--   함수    register_alighting_evaluation_work, register_alighting_settlement_batch, cleanup_alighting_settlement_batch,
--           track_alighting_batch_changes, track_alighting_observation_changes, invalidate_alighting_settlement,
--           enqueue_alighting_settlement_batch
-- 실행 전 조건(아래 가드가 확인하고, 맞지 않으면 아무것도 바꾸지 않고 멈춘다):
--   가. 옛 정산이 멈췄다. 최근 sal434.quiet_minutes(기본 15분) 안에 옛 정산이 남긴 흔적이 없어야 한다.
--       alighting_settlement_projection.updated_at(근거를 저장할 때마다 찍는다), alighting_settlement_batch_work.lease_until
--       (배치를 잡을 때 지금+2분으로 찍는다)을 본다. 옛 정산은 진행이 없어도 settlement-interval(60초)마다 돌기 때문에
--       15분이면 돌고 있는 정산은 반드시 흔적을 남긴다.
--   나. 옛 예보 경로가 멈췄다. forecast_publication의 최근 발행(published_at)이 같은 창 안에 없어야 한다. 옛 예보가 아직
--       seat_forecast에 쓰면 이 트리거가 정산 대기 줄을 만들어야 하기 때문이다.
--   다. 옛 정산·예보만 쓰는 테이블의 INSERT·UPDATE 통계가 sal434.observe_seconds(기본 90초) 동안 늘지 않는다
--       (pg_stat_user_tables의 n_tup_ins·n_tup_upd). 정산 결과 테이블에는 시각 인덱스가 없어 이 방법으로 본다.
--       수집이 쓰는 관측 두 테이블과 트리거가 쓰는 세대 표는 대상에서 뺀다. DELETE는 세지 않는다. 이 차례에는 옛 이관 앱이
--       아직 지난 날짜의 예보를 지울 수 있기 때문이다.
--   라. 새 채점이 돌고 있다. stop_pass의 마지막 닫힘(closed_at)이 24시간 안에 있어야 한다.
--   두 창은 SET sal434.quiet_minutes = '<분>'; SET sal434.observe_seconds = '<초>'; 로 바꿀 수 있다. 줄이면 그 이유를 기록한다.
-- 잡는 잠금과 예상 시간: 다의 관찰 동안은 잠금이 없다. 그 뒤 DROP TRIGGER가 다섯 테이블에 ACCESS EXCLUSIVE를 잡는다.
--   카탈로그만 바꾸므로 잠금을 얻으면 바로 끝난다. lock_timeout 5초(수집 트랜잭션은 짧다). 전체 약 90초+α.
-- 여러 번 실행해도 된다. 없는 트리거·함수는 건너뛴다(이미 지워졌으면 가드도 건너뛴다).
-- 이 뒤로 달라지는 것: 예측 줄에 정산 대기 줄이 더 생기지 않고, 관측이 바뀌어도 정산 근거가 무효 표시되지 않는다.
-- 되돌리기: 없다. 이 트리거에 기대는 코드가 든 배포본으로는 돌아갈 수 없게 된다. 그래서 테이블 삭제(sal434-03) 바로 앞에 실행한다.
-- 실행: psql -X -v ON_ERROR_STOP=1 -f sal434-02-drop-legacy-triggers.sql
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10min';
SET LOCAL search_path = public;

DO $$
DECLARE
    quiet interval := make_interval(mins => coalesce(nullif(current_setting('sal434.quiet_minutes', true), '')::int, 15));
    observe_seconds int := coalesce(nullif(current_setting('sal434.observe_seconds', true), '')::int, 90);
    -- 옛 정산·옛 예보 코드만 쓰는 테이블. 관측 두 테이블(수집)과 alighting_settlement_generation(트리거)은 뺀다.
    watched constant text[] := ARRAY['alighting_forecast_evaluation_result', 'alighting_forecast_evaluation_work',
        'alighting_settlement_observation', 'alighting_settlement_pass', 'alighting_settlement_queue_scan',
        'seat_forecast', 'forecast_publication'];
    remaining int;
    latest timestamptz;
    before jsonb;
    after jsonb;
    changed text;
BEGIN
    SELECT count(*) INTO remaining FROM pg_trigger
     WHERE NOT tgisinternal AND tgname IN ('trg_alighting_evaluation_work', 'trg_alighting_settlement_batch',
         'trg_alighting_settlement_batch_cleanup', 'trg_alighting_batch_insert', 'trg_alighting_batch_update',
         'trg_alighting_batch_delete', 'trg_alighting_observation_insert', 'trg_alighting_observation_update',
         'trg_alighting_observation_delete');
    IF remaining = 0 THEN
        RAISE NOTICE 'SAL-434: 옛 정산 트리거가 이미 없다. 남은 함수만 정리한다';
        RETURN;
    END IF;
    IF quiet < interval '1 minute' OR observe_seconds < 1 THEN
        RAISE EXCEPTION 'SAL-434: sal434.quiet_minutes는 1 이상, sal434.observe_seconds는 1 이상이어야 한다';
    END IF;

    -- 가. 옛 정산의 흔적.
    IF to_regclass('public.alighting_settlement_projection') IS NOT NULL THEN
        SELECT max(updated_at) INTO latest FROM alighting_settlement_projection;
        IF latest > now() - quiet THEN
            RAISE EXCEPTION 'SAL-434: 옛 정산이 % 에 근거를 저장했다. 옛 정산을 멈추고 % 뒤에 다시 실행한다', latest, quiet;
        END IF;
    END IF;
    IF to_regclass('public.alighting_settlement_batch_work') IS NOT NULL THEN
        SELECT max(lease_until) INTO latest FROM alighting_settlement_batch_work;
        IF latest > now() - quiet THEN
            RAISE EXCEPTION 'SAL-434: 옛 정산이 배치를 잡았다(임대 만료 %). 옛 정산을 멈추고 다시 실행한다', latest;
        END IF;
    END IF;

    -- 나. 옛 예보의 발행. 노선 판본마다 ix_publication_latest의 맨 앞만 읽는다.
    IF to_regclass('public.forecast_publication') IS NOT NULL THEN
        SELECT max(p.published_at) INTO latest
          FROM route_version v
          CROSS JOIN LATERAL (SELECT published_at FROM forecast_publication f WHERE f.route_version_id = v.id
                               ORDER BY f.observed_at DESC, f.source_batch_id DESC LIMIT 1) p;
        IF latest > now() - quiet THEN
            RAISE EXCEPTION 'SAL-434: 옛 예보가 % 에 발행했다. FORECAST_PIPELINE=v2 를 확인한다', latest;
        END IF;
    END IF;

    -- 라. 새 채점이 통과를 닫고 있다.
    IF to_regclass('public.stop_pass') IS NULL THEN
        RAISE EXCEPTION 'SAL-434: stop_pass 가 없다. V81이 적용된 DB에서만 실행한다';
    END IF;
    SELECT max(s.closed_at) INTO latest
      FROM route r CROSS JOIN LATERAL (SELECT closed_at FROM stop_pass p WHERE p.route_id = r.id
                                       ORDER BY p.closed_at DESC LIMIT 1) s;
    IF latest IS NULL OR latest < now() - interval '24 hours' THEN
        RAISE EXCEPTION 'SAL-434: 새 채점의 통과 기록이 24시간 안에 없다(마지막 %). FORECAST_SCORING을 확인한다', latest;
    END IF;

    -- 다. 쓰기 통계를 관찰한다. 다른 세션의 통계는 트랜잭션이 끝난 뒤 최대 1초 안에 반영된다.
    SELECT jsonb_object_agg(relname, n_tup_ins + n_tup_upd) INTO before
      FROM pg_stat_user_tables WHERE schemaname = 'public' AND relname = ANY (watched);
    PERFORM pg_sleep(observe_seconds);
    PERFORM pg_stat_clear_snapshot();
    SELECT jsonb_object_agg(relname, n_tup_ins + n_tup_upd) INTO after
      FROM pg_stat_user_tables WHERE schemaname = 'public' AND relname = ANY (watched);
    SELECT string_agg(key || ' +' || ((after ->> key)::bigint - (before ->> key)::bigint), ', ') INTO changed
      FROM jsonb_object_keys(after) AS key
     WHERE (after ->> key)::bigint <> coalesce((before ->> key)::bigint, 0);
    IF changed IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: % 초 동안 옛 정산·예보 테이블에 쓰기가 있었다: %', observe_seconds, changed;
    END IF;
    RAISE NOTICE 'SAL-434: 옛 정산·예보가 멈춘 것을 확인했다(흔적 창 %, 쓰기 관찰 % 초)', quiet, observe_seconds;
END $$;

DROP TRIGGER IF EXISTS trg_alighting_evaluation_work ON seat_forecast;
DROP TRIGGER IF EXISTS trg_alighting_settlement_batch ON alighting_forecast_evaluation_work;
DROP TRIGGER IF EXISTS trg_alighting_settlement_batch_cleanup ON alighting_forecast_evaluation_work;
DROP TRIGGER IF EXISTS trg_alighting_batch_insert ON observation_batch;
DROP TRIGGER IF EXISTS trg_alighting_batch_update ON observation_batch;
DROP TRIGGER IF EXISTS trg_alighting_batch_delete ON observation_batch;
DROP TRIGGER IF EXISTS trg_alighting_observation_insert ON vehicle_observation;
DROP TRIGGER IF EXISTS trg_alighting_observation_update ON vehicle_observation;
DROP TRIGGER IF EXISTS trg_alighting_observation_delete ON vehicle_observation;

DROP FUNCTION IF EXISTS register_alighting_evaluation_work();
DROP FUNCTION IF EXISTS register_alighting_settlement_batch();
DROP FUNCTION IF EXISTS cleanup_alighting_settlement_batch();
DROP FUNCTION IF EXISTS track_alighting_batch_changes();
DROP FUNCTION IF EXISTS track_alighting_observation_changes();
DROP FUNCTION IF EXISTS invalidate_alighting_settlement(bigint[], timestamptz[]);
DROP FUNCTION IF EXISTS enqueue_alighting_settlement_batch(bigint, varchar, timestamptz);

-- 남은 사용자 트리거. route_display·route_source·vehicle_capacity의 것만 남아야 한다.
SELECT tgrelid::regclass AS table_name, tgname, tgfoid::regproc AS function_name
  FROM pg_trigger WHERE NOT tgisinternal ORDER BY 1, 2;
COMMIT;
