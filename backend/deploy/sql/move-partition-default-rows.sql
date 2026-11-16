-- 날짜 파티션 테이블의 기본 파티션(<이름>_default)에 든 줄을 제 날짜 파티션(<이름>_pYYYYMMDD)으로 옮긴다. 자동 실행하지 않는다.
--
-- 왜: 날짜 파티션이 없을 때 들어온 줄은 기본 파티션으로 간다. 그 줄이 남아 있는 동안 PostgreSQL은 그 날짜의 파티션을 만들지
--     못한다(SQLSTATE 23514. worker 로그 event=partition_create_failed reason=default_rows). 이관 앱은 기본 파티션을 내보내지
--     않으므로 그 날짜의 줄은 S3 보관본에 들어가지 않고 DB에서도 지워지지 않는다.
-- 언제: worker 지표 salmonbus_partition_default_rows{table}이나 이관 앱 지표 salmonbus_archive_partition_default_rows{table}이
--       0보다 클 때. 파티션을 만들지 못한 원인(권한, 잠금 대기 등)을 먼저 없앤 뒤 실행한다.
--       순서와 뒤 확인은 RUNBOOK 「기본 파티션에 든 줄 옮기기」에 있다.
-- 대상: forecast_run, forecast_record, stop_pass, pass_label, forecast_unresolved, observation_batch, vehicle_observation.
--       한 번 실행에 한 테이블만 옮긴다. 같은 세션에서 아래 값을 넣는다.
--         SET salmonbus.default_rows_table = '<테이블 이름>';
--         SET salmonbus.default_rows_date = '<YYYY-MM-DD>';     -- 선택. 그 날짜의 줄만 옮긴다
--         SET salmonbus.default_rows_timeout = '<예: 10min>';   -- 선택. 잠금을 쥐는 시간의 한도. 기본 60s
--       테이블을 넣지 않으면 아무것도 바꾸지 않고 일곱 테이블의 기본 파티션에 든 줄 수를 날짜별로 알리고 끝난다.
-- 실행 전 조건:
--   - 실행 계정이 그 테이블의 소유자다(운영은 worker 계정). 새 파티션은 만든 계정의 것이 되는데, worker의 파티션 유지 작업과
--     이관 앱의 떼어 지우기가 소유자여야 하기 때문이다.
--   - 옮길 날짜가 내보내기 장부(daily_export)에 없다. 장부에 있는 날짜의 줄은 그 날짜를 이미 내보낸 뒤에 들어온 줄이다.
--     같은 날짜의 파티션을 다시 만들면 이관 앱이 LEDGER_DROPPED_PARTITION_PRESENT나 ROW_COUNT_CHANGED로 멈춘다. 그런 날짜는
--     옮기지 않고 경고만 남긴다. 처리 방법은 사람이 정한다.
-- 하는 일(한 트랜잭션. 중간에 실패하면 아무것도 바뀌지 않는다):
--   1. 부모 테이블에 ACCESS EXCLUSIVE 잠금을 잡는다.
--   2. 기본 파티션에 든 날짜마다
--      가. 그 날짜의 줄을 기본 파티션에서 지우며 임시 테이블에 담는다.
--      나. worker의 파티션 유지 작업과 같은 문장(CREATE TABLE ... PARTITION OF)으로 날짜 파티션을 만든다. 이름, 경계, 인덱스,
--          제약이 유지 작업이 만든 파티션과 같다. 기본 파티션에 그 날짜의 줄이 없어진 뒤라 만들 수 있다.
--      다. 임시 테이블의 줄을 부모 테이블에 넣는다. 열 값은 그대로다(IDENTITY 열인 id도 새로 받지 않는다).
--      라. 확인한다. 지운 줄 수 = 넣은 줄 수 = 새 파티션의 줄 수이고, 기본 파티션에 그 날짜의 줄이 없고, 임시 테이블의 줄마다
--          유일 키가 같은 새 파티션의 줄이 모든 열의 저장 값까지 같다(줄 전체를 견주는 *= 연산자). 하나라도 어긋나면 오류로
--          멈추고 전부 되돌린다.
--      마. 새 파티션의 통계를 모은다(ANALYZE).
-- 잡는 잠금과 걸리는 시간:
--   - 부모 테이블에 ACCESS EXCLUSIVE(파티션을 만들 때 기본 파티션과 새 파티션에도 잡힌다). lock_timeout 5초.
--     쥐고 있는 동안 그 테이블을 거치는 읽기와 쓰기(수집, 예보, 채점, API 조회)가 기다린다. 다른 여섯 테이블은 막지 않는다.
--   - 기다리던 쓰기는 COMMIT 뒤 새 파티션으로 들어간다. 부모를 잠그지 않고 기본 파티션만 잠그면 기다리던 쓰기가
--     "violates partition constraint"로 실패하거나 교착으로 한쪽이 취소된다(둘 다 PostgreSQL 18에서 실험으로 확인). 그래서 부모를
--     먼저, 하나만 잠근다. 한 번에 한 테이블만 옮기는 것도 부모 잠금을 둘 이상 쥐지 않기 위해서다.
--   - 쥐는 시간은 옮기는 줄 수에 비례한다. 줄을 두 번 쓰고(임시 테이블, 새 파티션) 두 번 읽어 견준다. 로컬 PostgreSQL 18에서
--     forecast_record 20만 줄(배열 원소 20개씩)에 2~10초, 40만 줄에 약 27초였다. 줄이 메모리에 다 들어가지 않으면 디스크 속도에
--     묶인다. FK를 줄마다 확인하는 vehicle_observation은 20만 줄에 2~3초였다. 운영 DB에서는 재지 않았다. 먼저 테이블을
--     넣지 않고 실행해 줄 수를 본다.
--   - statement_timeout(기본 60초, salmonbus.default_rows_timeout으로 바꾼다)을 넘으면 되돌리고 끝난다. API의 DB 소켓 제한이
--     60초라 그보다 오래 쥐면 그 테이블을 읽는 API 요청이 실패한다. 줄이 많으면 수집이 뜸한 시간(02~05시)에 한도를 늘려
--     실행하거나 salmonbus.default_rows_date로 날짜를 하나씩 옮긴다.
--   - worker의 파티션 유지 작업이나 이관 앱의 떼어 지우기가 이 잠금과 겹치면 그쪽이 잠금 대기 시간 초과로 한 번 미뤄지고
--     (event=partition_create_failed reason=lock_timeout, status=DEFERRED reason=LOCK_TIMEOUT) 다음 회차에 다시 한다.
--   - 저장 공간: 끝날 때까지 옮기는 줄의 네 배쯤이 더 든다(임시 테이블, 새 파티션, 견줄 때 쓰는 임시 파일). 기본 파티션에서
--     지운 자리는 autovacuum이 치운다.
-- 여러 번 실행해도 된다. 기본 파티션에 옮길 줄이 없으면 알리고 끝난다.
-- 실행: psql -X -v ON_ERROR_STOP=1 -c "SET salmonbus.default_rows_table = '...'" -f 이 파일
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path = public;
-- 아래 DO 문 전체의 제한 시간이다. 잠금을 쥐는 시간은 이 값을 넘지 않는다.
SELECT set_config('statement_timeout',
    coalesce(nullif(current_setting('salmonbus.default_rows_timeout', true), ''), '60s'), true) AS statement_timeout;

DO $$
DECLARE
    tables constant text[] := ARRAY['forecast_run', 'forecast_record', 'stop_pass', 'pass_label', 'forecast_unresolved',
        'observation_batch', 'vehicle_observation'];
    target constant text := nullif(current_setting('salmonbus.default_rows_table', true), '');
    only_date constant date := nullif(current_setting('salmonbus.default_rows_date', true), '')::date;
    t text;
    default_partition text;
    day_partition text;
    columns text;
    key_match text;
    dates date[];
    d date;
    r record;
    found_rows boolean;
    started timestamptz;
    deleted bigint;
    inserted bigint;
    in_partition bigint;
    left_behind bigint;
    same bigint;
    moved_dates integer := 0;
    skipped_dates integer := 0;
BEGIN
    IF target IS NULL THEN
        FOREACH t IN ARRAY tables LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = to_regclass('public.' || t) AND relkind = 'p')
               OR to_regclass('public.' || t || '_default') IS NULL THEN
                RAISE NOTICE 'DEFAULT-ROWS: % 는 기본 파티션을 둔 파티션 테이블이 아니다. 대상이 아니다', t;
                CONTINUE;
            END IF;
            found_rows := false;
            FOR r IN EXECUTE format('SELECT source_date, count(*) AS n FROM %I GROUP BY source_date ORDER BY source_date',
                    t || '_default') LOOP
                found_rows := true;
                RAISE NOTICE 'DEFAULT-ROWS: % 기본 파티션에 % 의 줄 % 개', t, r.source_date, r.n;
            END LOOP;
            IF NOT found_rows THEN
                RAISE NOTICE 'DEFAULT-ROWS: % 기본 파티션은 비어 있다', t;
            END IF;
        END LOOP;
        RAISE NOTICE 'DEFAULT-ROWS: 바꾼 것이 없다. 옮기려면 SET salmonbus.default_rows_table = ''<테이블 이름>''; 을 넣고 다시 실행한다';
        RETURN;
    END IF;

    IF NOT target = ANY (tables) THEN
        RAISE EXCEPTION 'DEFAULT-ROWS: % 는 날짜 파티션을 두는 테이블이 아니다. 고를 수 있는 테이블: %', target,
            array_to_string(tables, ', ');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = to_regclass('public.' || target) AND relkind = 'p') THEN
        RAISE NOTICE 'DEFAULT-ROWS: % 는 파티션 테이블이 아니다. 바꾸는 것 없이 끝난다', target;
        RETURN;
    END IF;
    default_partition := target || '_default';
    IF NOT EXISTS (SELECT 1 FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                    WHERE i.inhparent = target::regclass AND c.oid = to_regclass('public.' || default_partition)
                      AND pg_get_expr(c.relpartbound, c.oid) = 'DEFAULT') THEN
        RAISE EXCEPTION 'DEFAULT-ROWS: % 가 % 의 기본 파티션이 아니다', default_partition, target;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class WHERE oid = target::regclass
                  AND relowner <> (SELECT oid FROM pg_roles WHERE rolname = current_user)) THEN
        RAISE EXCEPTION 'DEFAULT-ROWS: % 의 소유자로 실행해야 한다(지금 계정 %)', target, current_user;
    END IF;

    -- 줄이 없으면 잠금을 잡지 않고 끝낸다.
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE $1 IS NULL OR source_date = $1)', default_partition)
       INTO found_rows USING only_date;
    IF NOT found_rows THEN
        RAISE NOTICE 'DEFAULT-ROWS: % 기본 파티션에 옮길 줄이 없다. 바꾸는 것 없이 끝난다', target;
        RETURN;
    END IF;

    -- 부모만 잠근다. 줄은 모두 부모를 거쳐 읽고 쓰므로 이것으로 충분하고, 기다리던 쓰기는 COMMIT 뒤 새 파티션을 본다.
    EXECUTE format('LOCK TABLE ONLY %I IN ACCESS EXCLUSIVE MODE', target);

    EXECUTE format('SELECT array_agg(DISTINCT source_date ORDER BY source_date) FROM %I'
        ' WHERE $1 IS NULL OR source_date = $1', default_partition) INTO dates USING only_date;
    IF dates IS NULL THEN
        RAISE NOTICE 'DEFAULT-ROWS: % 기본 파티션에 옮길 줄이 없다. 바꾸는 것 없이 끝난다', target;
        RETURN;
    END IF;

    SELECT string_agg(quote_ident(attname), ', ' ORDER BY attnum) INTO columns
      FROM pg_attribute
     WHERE attrelid = target::regclass AND attnum > 0 AND NOT attisdropped AND attgenerated = '';
    -- 옮기기 전과 뒤의 줄을 짝짓는 유일 키다. 기본 키, 없으면 열이 모두 NOT NULL인 유일 인덱스 가운데 열이 가장 적은 것이다.
    SELECT string_agg(format('before.%1$I = after.%1$I', a.attname), ' AND ' ORDER BY k.position) INTO key_match
      FROM (SELECT i.indrelid, i.indkey, i.indnkeyatts
              FROM pg_index i
             WHERE i.indrelid = target::regclass AND i.indisunique AND i.indpred IS NULL AND i.indexprs IS NULL
               AND NOT EXISTS (SELECT 1 FROM unnest(i.indkey) WITH ORDINALITY AS c(attnum, position)
                                 JOIN pg_attribute n ON n.attrelid = i.indrelid AND n.attnum = c.attnum
                                WHERE c.position <= i.indnkeyatts AND NOT n.attnotnull)
             ORDER BY i.indisprimary DESC, i.indnkeyatts, i.indexrelid::regclass::text
             LIMIT 1) u
     CROSS JOIN LATERAL unnest(u.indkey) WITH ORDINALITY AS k(attnum, position)
      JOIN pg_attribute a ON a.attrelid = u.indrelid AND a.attnum = k.attnum
     WHERE k.position <= u.indnkeyatts;
    IF key_match IS NULL THEN
        RAISE EXCEPTION 'DEFAULT-ROWS: % 에 줄을 짝지을 유일 키가 없어 옮긴 결과를 확인할 수 없다', target;
    END IF;
    EXECUTE format('CREATE TEMP TABLE default_rows_moved (LIKE %I) ON COMMIT DROP', target);

    FOREACH d IN ARRAY dates LOOP
        day_partition := target || '_p' || to_char(d, 'YYYYMMDD');
        IF EXISTS (SELECT 1 FROM daily_export WHERE table_name = target AND source_date = d AND segment = 0) THEN
            RAISE WARNING 'DEFAULT-ROWS: % 의 % 는 내보내기 장부에 이미 있다. 그 날짜를 내보낸 뒤에 들어온 줄이라 옮기지 않는다', target, d;
            skipped_dates := skipped_dates + 1;
            CONTINUE;
        END IF;
        IF to_regclass('public.' || day_partition) IS NOT NULL THEN
            RAISE EXCEPTION 'DEFAULT-ROWS: % 라는 관계가 이미 있는데 기본 파티션에 그 날짜의 줄이 있다. 사람이 확인한다', day_partition;
        END IF;

        started := clock_timestamp();
        TRUNCATE pg_temp.default_rows_moved;
        EXECUTE format('WITH gone AS (DELETE FROM %I WHERE source_date = $1 RETURNING %s)'
            ' INSERT INTO pg_temp.default_rows_moved (%s) SELECT %s FROM gone',
            default_partition, columns, columns, columns) USING d;
        GET DIAGNOSTICS deleted = ROW_COUNT;

        EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)', day_partition, target, d, d + 1);
        EXECUTE format('INSERT INTO %I (%s) OVERRIDING SYSTEM VALUE SELECT %s FROM pg_temp.default_rows_moved',
            target, columns, columns);
        GET DIAGNOSTICS inserted = ROW_COUNT;
        EXECUTE format('ANALYZE %I', day_partition);

        EXECUTE format('SELECT count(*) FROM %I', day_partition) INTO in_partition;
        EXECUTE format('SELECT count(*) FROM %I WHERE source_date = $1', default_partition) INTO left_behind USING d;
        -- 키가 유일하고 줄 수가 같으므로, 짝지은 줄이 모두 같으면 옮기기 전과 뒤의 줄 모음이 같다.
        EXECUTE format('SELECT count(*) FROM pg_temp.default_rows_moved before JOIN %I after ON %s WHERE before *= after',
            day_partition, key_match) INTO same;
        IF deleted = 0 OR inserted <> deleted OR in_partition <> deleted OR left_behind <> 0 OR same <> deleted THEN
            RAISE EXCEPTION 'DEFAULT-ROWS: % 의 % 를 옮긴 결과가 맞지 않아 되돌린다(지운 줄 %, 넣은 줄 %, 새 파티션의 줄 %, 기본 파티션에 남은 줄 %, 값까지 같은 줄 %)',
                target, d, deleted, inserted, in_partition, left_behind, same;
        END IF;
        moved_dates := moved_dates + 1;
        RAISE NOTICE 'DEFAULT-ROWS: % 의 % 줄 % 개를 % 로 옮겼다. 줄 수와 값이 옮기기 전과 같다(% 초)', target, d, deleted,
            day_partition, round(extract(epoch FROM clock_timestamp() - started)::numeric, 1);
    END LOOP;

    EXECUTE format('SELECT count(*) FROM %I', default_partition) INTO left_behind;
    RAISE NOTICE 'DEFAULT-ROWS: % 날짜 % 개를 옮기고 % 개를 건너뛰었다. 기본 파티션에 남은 줄 % 개', target, moved_dates,
        skipped_dates, left_behind;
END $$;
COMMIT;
