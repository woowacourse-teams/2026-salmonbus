-- SAL-434 관측 두 테이블(observation_batch, vehicle_observation)을 날짜 파티션 테이블로 바꾼다. 자동 실행하지 않는다.
--
-- 언제: RUNBOOK "SAL-434 전환 절차" 8번 차례. sal434-03(옛 테이블 삭제)과 sal434-04a(준비)가 끝난 뒤, dev에서 같은 절차를
--       리허설하고 승인을 받은 뒤 실행한다. 수집이 뜸한 시간(02~05시)에 한다.
-- 실행 전 조건:
--   - 두 테이블을 가리키는 FK가 두 테이블 사이의 fk_observation_batch 말고는 없다. 운영에만 있는
--     historical_import_record·migration_source_record의 FK는 drop-historical-import-batch-fks.sql로 먼저 지운다.
--   - 두 테이블에 사용자 트리거가 없다(sal434-02 실행 완료). 옛 이관 앱이 멈춰 있다.
--   - sal434-04a의 마지막 조회에서 인덱스 다섯 개가 모두 valid이고 확인 제약 두 개가 validated다.
--   - 실행 계정이 두 테이블의 소유자다(운영은 worker 계정이 모든 테이블의 소유자다).
--   - 실행자가 같은 세션에서 아래 두 값을 넣는다. 넣지 않으면 아무것도 바꾸지 않고 멈춘다.
--       SET sal434.confirm_partition = '<오늘 한국 날짜 YYYY-MM-DD>';
--       SET sal434.count_rows = 'exact';   -- 또는 'storage'
--     exact: 잠금을 잡은 채로 두 테이블을 바꾸기 전과 뒤에 한 번씩 센다(줄 수 대조). 운영 규모에서는 그동안 수집 쓰기가 기다린다.
--     storage: 세지 않고, 옛 테이블의 저장 파일(relfilenode)이 그대로 파티션이 됐는지만 본다. 이 SQL은 줄을 옮기지 않으므로
--     저장 파일이 같으면 줄도 같다. 운영은 dev 리허설에서 exact에 걸린 시간을 보고 고른다.
-- 하는 일(한 트랜잭션):
--   1. 두 테이블에 ACCESS EXCLUSIVE 잠금을 잡는다.
--   2. vehicle_observation → observation_batch 합성 FK(fk_observation_batch)를 지운다. 파티션 부모를 가리키는 FK는
--      부모의 유일 제약에 파티션 키가 있어야 하는데, 관측 줄의 source_date가 배치의 source_date와 같다는 보장이 없어서다
--      (관측은 응답을 받은 날, 배치는 예약한 날의 기본값을 받는다. 자정 근처에서 다를 수 있다).
--   3. 옛 테이블 이름을 <이름>_legacy로 바꾸고, 같은 열·기본값·확인 제약을 가진 파티션 부모를 원래 이름으로 만든다.
--      - IDENTITY: PostgreSQL 18은 IDENTITY 열이 있는 테이블을 파티션으로 붙이지 못한다(실험으로 확인).
--        옛 테이블의 IDENTITY를 떼고, 부모에 IDENTITY를 새로 달아 옛 시퀀스의 다음 값(과 max(id)+1 중 큰 값)부터 잇는다.
--      - source_date 기본값: 부모는 (now() AT TIME ZONE 'Asia/Seoul')::date. 파티션 부모의 열 기본값으로 쓸 수 있고,
--        행이 파티션을 고르기 전에 계산된다(실험으로 확인). 그래서 수집 코드를 고치지 않아도 새 줄이 그날 파티션으로 간다.
--      - 유일 제약: 파티션 키를 넣은 것으로 다시 잡는다. id(id, source_date), 시도 키(route_version_id, attempt_key, source_date),
--        배치 안 줄 번호(observation_batch_id, source_row_number, source_date), 배치 안 차량(부분 유일 인덱스).
--        한 배치의 관측은 한 트랜잭션(같은 now())에서 들어가므로 같은 날짜가 되어, 배치 안 유일성의 뜻은 그대로다.
--        옛 PK(id)와 ux_batch_context·ux_observation_context는 옛 파티션에만 남는다(부모 PK는 두지 않는다. 옛 파티션에
--        PK가 이미 있어 같은 부모 PK로 붙일 수 없다는 것을 실험으로 확인했다).
--      - 일반 인덱스: ix_batch_recent_history, ix_batch_forecast_candidates, ix_batch_unfinished_recovery,
--        ix_batch_confirmed_history, ix_observation_vehicle_capacity는 같은 정의로 부모에 만들고 옛 인덱스를 붙인다
--        (옛 인덱스 이름은 <이름>_legacy로 바뀐다). 운영에만 있는 수동 인덱스(ix_archive_* 등)는 옛 파티션에만 남긴다.
--      - 나가는 FK(route_version, route_stop, 운영의 historical_import_batch 등)는 부모에 같은 정의로 만들고 옛 FK를 붙인다.
--        붙일 때 다시 확인하지 않는다(실험으로 확인).
--      - NOT VALID 확인 제약(ck_observation_seat_quality_status)은 부모에도 NOT VALID로 만든다. 확인된 제약으로 만들면
--        ATTACH가 거절하고, pg_get_constraintdef로 되읽은 식으로 만들어도 저장된 식의 모양이 달라 "다른 정의"로 거절한다
--        (둘 다 실험으로 확인). 그래서 V66의 원래 식을 이 파일에 적어 두고 그것으로 만든다. 모르는 NOT VALID 제약이 있으면 멈춘다.
--      - 권한(GRANT)은 옛 테이블의 것을 부모에 그대로 준다.
--   4. 옛 테이블을 FOR VALUES FROM ('2000-01-01') TO ('2000-01-02') 파티션으로 붙인다. 04a의 확인 제약 덕분에 줄을 읽지 않는다.
--      붙이는 동안 새 인덱스가 만들어지면(정의가 맞지 않으면 PostgreSQL이 만든다) 실패로 보고 되돌린다.
--   5. 오늘(한국)부터 사흘 뒤까지의 날짜 파티션 <이름>_pYYYYMMDD와 기본 파티션 <이름>_default를 만든다.
--   6. 두 테이블을 읽는 뷰(operational_observation 등)를 같은 정의로 다시 만든다. 뷰는 이름이 아니라 OID로 테이블을
--      가리켜서 그대로 두면 옛 파티션만 본다.
-- 잡는 잠금과 예상 시간: 두 테이블과 그 뷰에 ACCESS EXCLUSIVE. lock_timeout 5초. storage면 카탈로그만 바꿔 로컬 100만 줄에서
--   1초 미만이다. exact면 두 테이블을 두 번씩 읽는다(로컬 100만 줄 약 0.2초, 운영 관측 6.5GB는 분 단위로 본다. 추정).
--   이 동안 수집·예보·API 조회가 기다린다. statement_timeout 15분.
-- 여러 번 실행해도 된다. 이미 파티션 테이블이면 알리고 끝난다. 날짜 파티션은 없을 때만 만든다.
-- 되돌리기: sal434-04-rollback-unpartition-observation-tables.sql. 날짜 파티션에 들어온 줄을 옛 테이블로 옮기고
--   원래 이름·IDENTITY·FK를 되돌린다.
-- 전환 뒤 해야 하는 것: worker의 날짜 파티션 유지 작업은 지금 새 예보 테이블 다섯 개만 다룬다. 두 테이블을 그 목록에 넣지
--   않으면 사흘 뒤부터 새 줄은 기본 파티션으로 들어간다(동작은 한다). 부모 통계는 ANALYZE observation_batch; 로 따로 모은다.
-- 실행: psql -X -v ON_ERROR_STOP=1 -c "SET sal434.confirm_partition = '...'" -c "SET sal434.count_rows = '...'" -f 이 파일
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '15min';
SET LOCAL search_path = public;

DO $$
DECLARE
    today constant date := (now() AT TIME ZONE 'Asia/Seoul')::date;
    legacy_date constant date := DATE '2000-01-01';
    confirm text := nullif(current_setting('sal434.confirm_partition', true), '');
    count_mode text := nullif(current_setting('sal434.count_rows', true), '');
    tables constant text[] := ARRAY['observation_batch', 'vehicle_observation'];
    -- 부모에 다시 만드는 일반 인덱스. 없는 것은 건너뛴다.
    copied_indexes constant text[] := ARRAY['ix_batch_recent_history', 'ix_batch_forecast_candidates',
        'ix_batch_unfinished_recovery', 'ix_batch_confirmed_history', 'ix_observation_vehicle_capacity'];
    -- 옛 파티션에만 남겨도 되는 유일 인덱스. 이 밖의 유일 인덱스가 있으면 뜻을 확인해야 하므로 멈춘다.
    legacy_only_unique constant text[] := ARRAY['pk_observation_batch', 'ux_batch_attempt', 'ux_batch_context',
        'ux_observation_batch_historical_semantic', 'pk_vehicle_observation', 'ux_observation_source_row',
        'ux_observation_context', 'ux_observation_vehicle_per_batch'];
    -- NOT VALID 확인 제약의 원래 식(V66__retire_trip_quality.sql).
    not_valid_checks constant jsonb := jsonb_build_object('ck_observation_seat_quality_status', $chk$
    (reported_remaining_seats IS NULL AND seat_quality_status IS NULL)
    OR (
        reported_remaining_seats >= 0 AND reported_remaining_seats IS NOT NULL
        AND seat_quality_status IN ('REVIEW', 'OUTLIER', 'HISTORICAL') AND seat_quality_status IS NOT NULL
        AND remaining_seats IS NULL
    )$chk$);
    prebuilt constant text[] := ARRAY['ux_observation_batch_id_date_legacy', 'ux_batch_attempt_date_legacy',
        'ux_vehicle_observation_id_date_legacy', 'ux_observation_source_row_date_legacy',
        'ux_observation_vehicle_per_batch_date_legacy'];
    t text;
    legacy text;
    kinds text;
    problems text;
    r record;
    view_defs jsonb := '[]'::jsonb;
    before_count jsonb := '{}'::jsonb;
    filenode jsonb := '{}'::jsonb;
    next_id bigint;
    identity_kind "char";
    seq regclass;
    seq_last bigint;
    seq_called boolean;
    index_oids oid[];
    def text;
    n bigint;
    d date;
BEGIN
    SELECT string_agg(coalesce(relkind::text, '-'), '' ORDER BY ord) INTO kinds
      FROM unnest(tables) WITH ORDINALITY AS x(name, ord)
      LEFT JOIN pg_class ON oid = to_regclass('public.' || name);
    IF kinds = 'pp' THEN
        IF to_regclass('public.observation_batch_legacy') IS NOT NULL
           AND to_regclass('public.vehicle_observation_legacy') IS NOT NULL
           AND (SELECT relispartition FROM pg_class WHERE oid = 'observation_batch_legacy'::regclass)
           AND (SELECT relispartition FROM pg_class WHERE oid = 'vehicle_observation_legacy'::regclass) THEN
            RAISE NOTICE 'SAL-434: 두 테이블은 이미 파티션 테이블이다. 바꾸는 것 없이 끝난다';
            RETURN;
        END IF;
        RAISE EXCEPTION 'SAL-434: 두 테이블이 파티션 테이블이지만 옛 파티션(<이름>_legacy)이 붙어 있지 않다. 상태를 사람이 확인한다';
    ELSIF kinds IS DISTINCT FROM 'rr' THEN
        RAISE EXCEPTION 'SAL-434: 두 테이블이 모두 일반 테이블이어야 한다(지금 relkind %). 중간 상태이므로 멈춘다', kinds;
    END IF;

    IF confirm IS DISTINCT FROM to_char(today, 'YYYY-MM-DD') THEN
        RAISE EXCEPTION 'SAL-434: 승인값이 없다. SET sal434.confirm_partition = ''%''; 를 넣고 다시 실행한다', to_char(today, 'YYYY-MM-DD');
    END IF;
    IF count_mode IS NULL OR count_mode NOT IN ('exact', 'storage') THEN
        RAISE EXCEPTION 'SAL-434: SET sal434.count_rows = ''exact'' 또는 ''storage'' 를 골라 넣는다(파일 머리 설명)';
    END IF;
    IF to_regclass('public.observation_batch_legacy') IS NOT NULL OR to_regclass('public.vehicle_observation_legacy') IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: <이름>_legacy 관계가 이미 있다. 이전 시도의 흔적이므로 사람이 확인한다';
    END IF;

    -- 소유자: 부모는 실행 계정 소유로 만들어지므로 같아야 한다.
    SELECT string_agg(relname || '(' || pg_get_userbyid(relowner) || ')', ', ') INTO problems
      FROM pg_class WHERE oid = ANY (ARRAY['observation_batch'::regclass, 'vehicle_observation'::regclass])
       AND relowner <> (SELECT oid FROM pg_roles WHERE rolname = current_user);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 테이블 소유자로 실행해야 한다: %', problems;
    END IF;

    -- 들어오는 FK: 두 테이블 사이의 것 말고는 없어야 한다.
    SELECT string_agg(conrelid::regclass || '.' || conname || ' -> ' || confrelid::regclass, ', ') INTO problems
      FROM pg_constraint
     WHERE contype = 'f' AND confrelid = ANY (ARRAY['observation_batch'::regclass, 'vehicle_observation'::regclass])
       AND NOT (conrelid = 'vehicle_observation'::regclass AND confrelid = 'observation_batch'::regclass);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 두 테이블을 가리키는 FK가 남아 있다(sal434-03, drop-historical-import-batch-fks.sql 먼저): %', problems;
    END IF;

    -- 트리거·규칙·정책·발행: 부모에 옮기지 않으므로 없어야 한다.
    SELECT string_agg(tgrelid::regclass || '.' || tgname, ', ') INTO problems
      FROM pg_trigger WHERE NOT tgisinternal
       AND tgrelid = ANY (ARRAY['observation_batch'::regclass, 'vehicle_observation'::regclass]);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 사용자 트리거가 남아 있다(sal434-02 먼저): %', problems;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_rewrite WHERE ev_class = ANY (ARRAY['observation_batch'::regclass, 'vehicle_observation'::regclass]))
       OR EXISTS (SELECT 1 FROM pg_policy WHERE polrelid = ANY (ARRAY['observation_batch'::regclass, 'vehicle_observation'::regclass]))
       OR EXISTS (SELECT 1 FROM pg_publication_rel WHERE prrelid = ANY (ARRAY['observation_batch'::regclass, 'vehicle_observation'::regclass]))
       OR EXISTS (SELECT 1 FROM pg_class WHERE oid = ANY (ARRAY['observation_batch'::regclass, 'vehicle_observation'::regclass])
                  AND (relrowsecurity OR relreplident NOT IN ('d'))) THEN
        RAISE EXCEPTION 'SAL-434: 규칙·행 보안·발행·복제 식별자 설정이 있다. 이 SQL은 그것을 옮기지 않는다';
    END IF;

    -- 04a 결과: 열, 확인 제약, 미리 만든 인덱스.
    FOREACH t IN ARRAY tables LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
             WHERE a.attrelid = t::regclass AND a.attname = 'source_date' AND NOT a.attisdropped AND a.attnotnull
               AND a.atttypid = 'date'::regtype AND pg_get_expr(d.adbin, d.adrelid) = '''2000-01-01''::date') THEN
            RAISE EXCEPTION 'SAL-434: %.source_date 가 없거나 모양이 다르다(sal434-04a 먼저)', t;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = t::regclass
                       AND conname = 'ck_' || t || '_legacy_date' AND convalidated) THEN
            RAISE EXCEPTION 'SAL-434: ck_%_legacy_date 가 없거나 확인되지 않았다(sal434-04a 먼저)', t;
        END IF;
    END LOOP;
    SELECT string_agg(name, ', ') INTO problems
      FROM unnest(prebuilt) AS name
     WHERE NOT EXISTS (SELECT 1 FROM pg_index i WHERE i.indexrelid = to_regclass('public.' || name)
                       AND i.indisvalid AND i.indisready AND i.indisunique);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 미리 만든 유일 인덱스가 없거나 유효하지 않다(sal434-04a 다시): %', problems;
    END IF;

    -- 유일 인덱스는 아는 것만 있어야 한다.
    SELECT string_agg(c.relname, ', ') INTO problems
      FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
     WHERE i.indrelid = ANY (ARRAY['observation_batch'::regclass, 'vehicle_observation'::regclass]) AND i.indisunique
       AND NOT c.relname = ANY (legacy_only_unique || prebuilt);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 알지 못하는 유일 인덱스가 있다. 파티션 뒤에 뜻을 지킬 방법을 정한 뒤 이 파일에 더한다: %', problems;
    END IF;

    -- 두 테이블을 직접 읽는 뷰. 일반 뷰만 다시 만들 수 있다.
    SELECT string_agg(v.oid::regclass::text, ', ') INTO problems
      FROM pg_depend dep JOIN pg_rewrite rw ON rw.oid = dep.objid JOIN pg_class v ON v.oid = rw.ev_class
     WHERE dep.classid = 'pg_rewrite'::regclass AND dep.refclassid = 'pg_class'::regclass
       AND dep.refobjid = ANY (ARRAY['observation_batch'::regclass, 'vehicle_observation'::regclass])
       AND v.oid <> dep.refobjid AND (v.relkind <> 'v' OR v.reloptions IS NOT NULL);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'SAL-434: 다시 만들 수 없는 뷰(구체화 뷰나 옵션이 있는 뷰)가 두 테이블을 읽는다: %', problems;
    END IF;
    SELECT coalesce(jsonb_agg(jsonb_build_object('name', x.view_name, 'def', x.view_def) ORDER BY x.view_name), '[]'::jsonb) INTO view_defs
      FROM (SELECT DISTINCT v.oid::regclass::text AS view_name, pg_get_viewdef(v.oid) AS view_def
              FROM pg_depend dep JOIN pg_rewrite rw ON rw.oid = dep.objid JOIN pg_class v ON v.oid = rw.ev_class
             WHERE dep.classid = 'pg_rewrite'::regclass AND dep.refclassid = 'pg_class'::regclass
               AND dep.refobjid = ANY (ARRAY['observation_batch'::regclass, 'vehicle_observation'::regclass])
               AND v.oid <> dep.refobjid) x;

    LOCK TABLE observation_batch, vehicle_observation IN ACCESS EXCLUSIVE MODE;

    FOREACH t IN ARRAY tables LOOP
        filenode := filenode || jsonb_build_object(t, pg_relation_filenode(t::regclass));
        IF count_mode = 'exact' THEN
            EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
            before_count := before_count || jsonb_build_object(t, n);
        END IF;
        RAISE NOTICE 'SAL-434: % 크기 % 예상 줄 수 % 정확한 줄 수 %', t, pg_size_pretty(pg_total_relation_size(t::regclass)),
            (SELECT reltuples::bigint FROM pg_class WHERE oid = t::regclass), coalesce(before_count ->> t, '(세지 않음)');
    END LOOP;

    -- 두 테이블 사이의 FK. 파티션 뒤에는 둘 수 없다(위 설명).
    FOR r IN SELECT conname FROM pg_constraint
              WHERE contype = 'f' AND conrelid = 'vehicle_observation'::regclass AND confrelid = 'observation_batch'::regclass LOOP
        RAISE NOTICE 'SAL-434: FK % 를 지운다', r.conname;
        EXECUTE format('ALTER TABLE vehicle_observation DROP CONSTRAINT %I', r.conname);
    END LOOP;

    FOREACH t IN ARRAY tables LOOP
        legacy := t || '_legacy';

        -- 미리 만든 유일 인덱스를 제약으로 바꾼다. 부모의 유일 제약은 제약이 붙은 인덱스만 붙인다(실험으로 확인).
        FOR r IN SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                  WHERE i.indrelid = t::regclass AND c.relname = ANY (prebuilt) AND i.indpred IS NULL
                    AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conindid = i.indexrelid) LOOP
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE USING INDEX %I', t, r.relname, r.relname);
        END LOOP;

        -- 다음 IDENTITY 값. 옛 시퀀스의 다음 값과 max(id)+1 가운데 큰 것.
        SELECT attidentity INTO identity_kind FROM pg_attribute WHERE attrelid = t::regclass AND attname = 'id';
        IF identity_kind NOT IN ('a', 'd') THEN
            RAISE EXCEPTION 'SAL-434: %.id 가 IDENTITY 열이 아니다', t;
        END IF;
        seq := pg_get_serial_sequence(t, 'id')::regclass;
        EXECUTE format('SELECT last_value, is_called FROM %s', seq) INTO seq_last, seq_called;
        EXECUTE format('SELECT coalesce(max(id), 0) + 1 FROM %I', t) INTO next_id;
        next_id := greatest(next_id, CASE WHEN seq_called THEN seq_last + 1 ELSE seq_last END);

        index_oids := ARRAY(SELECT indexrelid FROM pg_index WHERE indrelid = t::regclass);

        EXECUTE format('ALTER TABLE %I RENAME TO %I', t, legacy);
        FOR r IN SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                  WHERE i.indrelid = legacy::regclass AND c.relname = ANY (copied_indexes) LOOP
            EXECUTE format('ALTER INDEX %I RENAME TO %I', r.relname, left(r.relname || '_legacy', 63));
        END LOOP;
        EXECUTE format('ALTER TABLE %I ALTER COLUMN id DROP IDENTITY', legacy);

        EXECUTE format('CREATE TABLE %I (LIKE %I INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING STORAGE INCLUDING COMMENTS)'
            ' PARTITION BY RANGE (source_date)', t, legacy);
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', t, 'ck_' || t || '_legacy_date');
        -- NOT VALID 확인 제약은 부모에도 NOT VALID로 둔다. pg_get_constraintdef로 되읽은 식은 저장된 식과 모양이 달라
        -- ATTACH가 "다른 정의"로 거절하므로(실험으로 확인) 마이그레이션의 원래 식으로 만든다.
        FOR r IN SELECT conname FROM pg_constraint
                  WHERE conrelid = legacy::regclass AND contype = 'c' AND NOT convalidated LOOP
            IF NOT not_valid_checks ? r.conname THEN
                RAISE EXCEPTION 'SAL-434: 알지 못하는 NOT VALID 확인 제약 %.% 가 있다', t, r.conname;
            END IF;
            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', t, r.conname);
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%s) NOT VALID', t, r.conname, not_valid_checks ->> r.conname);
        END LOOP;
        EXECUTE format('ALTER TABLE %I ALTER COLUMN id ADD GENERATED %s AS IDENTITY (START WITH %s)',
            t, CASE identity_kind WHEN 'a' THEN 'ALWAYS' ELSE 'BY DEFAULT' END, next_id);
        EXECUTE format('ALTER TABLE %I ALTER COLUMN source_date SET DEFAULT ((now() AT TIME ZONE ''Asia/Seoul'')::date)', t);

        IF t = 'observation_batch' THEN
            ALTER TABLE observation_batch ADD CONSTRAINT ux_observation_batch_id_date UNIQUE (id, source_date);
            ALTER TABLE observation_batch ADD CONSTRAINT ux_batch_attempt_date UNIQUE (route_version_id, attempt_key, source_date);
        ELSE
            ALTER TABLE vehicle_observation ADD CONSTRAINT ux_vehicle_observation_id_date UNIQUE (id, source_date);
            ALTER TABLE vehicle_observation ADD CONSTRAINT ux_observation_source_row_date
                UNIQUE (observation_batch_id, source_row_number, source_date);
            CREATE UNIQUE INDEX ux_observation_vehicle_per_batch_date
                ON vehicle_observation (observation_batch_id, vehicle_id, source_date) WHERE vehicle_id IS NOT NULL;
        END IF;

        -- 일반 인덱스: 옛 정의의 테이블 이름만 부모로 바꿔 원래 이름으로 만든다.
        FOR r IN SELECT c.relname, pg_get_indexdef(i.indexrelid) AS def FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                  WHERE i.indrelid = legacy::regclass AND c.relname = ANY (ARRAY(SELECT left(x || '_legacy', 63) FROM unnest(copied_indexes) x)) LOOP
            def := regexp_replace(r.def, '^CREATE INDEX \S+ ON public\.' || legacy || ' ',
                format('CREATE INDEX %I ON public.%I ', left(r.relname, length(r.relname) - length('_legacy')), t));
            IF def = r.def THEN
                RAISE EXCEPTION 'SAL-434: 인덱스 정의를 옮기지 못했다: %', r.def;
            END IF;
            EXECUTE def;
        END LOOP;

        -- 나가는 FK.
        FOR r IN SELECT conname, pg_get_constraintdef(oid) AS def FROM pg_constraint
                  WHERE conrelid = legacy::regclass AND contype = 'f' LOOP
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I %s', t, r.conname, r.def);
        END LOOP;

        -- 권한.
        FOR r IN SELECT a.privilege_type, a.grantee, a.is_grantable
                   FROM pg_class c, aclexplode(c.relacl) a
                  WHERE c.oid = legacy::regclass AND a.grantee <> c.relowner LOOP
            EXECUTE format('GRANT %s ON %I TO %s%s', r.privilege_type, t,
                CASE WHEN r.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(r.grantee)) END,
                CASE WHEN r.is_grantable THEN ' WITH GRANT OPTION' ELSE '' END);
        END LOOP;

        EXECUTE format('ALTER TABLE %I ATTACH PARTITION %I FOR VALUES FROM (%L) TO (%L)', t, legacy, legacy_date, legacy_date + 1);

        -- 붙이면서 옛 파티션에 새 인덱스를 만들었다면 정의가 어긋난 것이다. 운영에서는 큰 인덱스를 잠금 안에서 만들게 되므로 멈춘다.
        SELECT string_agg(c.relname, ', ') INTO problems
          FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
         WHERE i.indrelid = legacy::regclass AND NOT i.indexrelid = ANY (index_oids);
        IF problems IS NOT NULL THEN
            RAISE EXCEPTION 'SAL-434: 옛 파티션을 붙이면서 인덱스를 새로 만들었다(정의 불일치): %', problems;
        END IF;
        SELECT string_agg(c.relname, ', ') INTO problems
          FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
         WHERE i.indrelid = t::regclass AND NOT i.indisvalid;
        IF problems IS NOT NULL THEN
            RAISE EXCEPTION 'SAL-434: 부모 인덱스가 유효하지 않다(옛 파티션의 인덱스가 붙지 않음): %', problems;
        END IF;
        SELECT string_agg(conname, ', ') INTO problems
          FROM pg_constraint WHERE conrelid = legacy::regclass AND contype = 'f' AND conparentid = 0;
        IF problems IS NOT NULL THEN
            RAISE EXCEPTION 'SAL-434: 옛 파티션의 FK가 부모 FK에 붙지 않았다: %', problems;
        END IF;
        IF pg_relation_filenode(legacy::regclass) IS DISTINCT FROM (filenode ->> t)::oid THEN
            RAISE EXCEPTION 'SAL-434: 옛 테이블의 저장 파일이 바뀌었다. 줄을 다시 쓴 것이므로 되돌린다';
        END IF;

        FOR n IN 0..3 LOOP
            d := today + n;
            EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                t || '_p' || to_char(d, 'YYYYMMDD'), t, d, d + 1);
        END LOOP;
        EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF %I DEFAULT', t || '_default', t);
    END LOOP;

    -- 뷰를 같은 정의로 다시 만든다. 정의 안의 테이블 이름이 이제 파티션 부모를 가리킨다.
    FOR r IN SELECT value ->> 'name' AS name, value ->> 'def' AS def FROM jsonb_array_elements(view_defs) LOOP
        EXECUTE format('CREATE OR REPLACE VIEW %s AS %s', r.name, r.def);
        IF EXISTS (SELECT 1 FROM pg_depend dep JOIN pg_rewrite rw ON rw.oid = dep.objid
                    WHERE dep.classid = 'pg_rewrite'::regclass AND rw.ev_class = r.name::regclass
                      AND dep.refobjid IN ('observation_batch_legacy'::regclass, 'vehicle_observation_legacy'::regclass)) THEN
            RAISE EXCEPTION 'SAL-434: 뷰 % 가 아직 옛 파티션을 직접 읽는다', r.name;
        END IF;
        RAISE NOTICE 'SAL-434: 뷰 % 를 파티션 부모 위로 다시 만들었다', r.name;
    END LOOP;

    IF count_mode = 'exact' THEN
        FOREACH t IN ARRAY tables LOOP
            EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
            IF n <> (before_count ->> t)::bigint THEN
                RAISE EXCEPTION 'SAL-434: % 줄 수가 다르다(전 %, 후 %)', t, before_count ->> t, n;
            END IF;
            RAISE NOTICE 'SAL-434: % 줄 수 전후 같음 %', t, n;
        END LOOP;
    END IF;
END $$;

-- 결과 확인: 부모와 파티션, 다음 IDENTITY 값.
SELECT parent.relname AS parent, child.relname AS partition, pg_get_expr(child.relpartbound, child.oid) AS bound
  FROM pg_inherits JOIN pg_class parent ON parent.oid = inhparent JOIN pg_class child ON child.oid = inhrelid
 WHERE parent.relname IN ('observation_batch', 'vehicle_observation')
 ORDER BY 1, 2;
COMMIT;
