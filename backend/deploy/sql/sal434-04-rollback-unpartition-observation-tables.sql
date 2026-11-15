-- SAL-434 sal434-04를 되돌린다. 자동 실행하지 않는다.
--
-- 언제: sal434-04 실행 뒤 수집·예보·API에 문제가 생겨 파티션 이전 모양으로 돌아가야 할 때. 승인을 받고 실행한다.
--       이관 앱이 첫 파티션(<이름>_initial)을 내보내 지운 뒤에는 되돌릴 수 없다. 마이그레이션(V84)이 처음부터 파티션 테이블로
--       만든 DB도 첫 파티션이 없으므로 대상이 아니다.
-- 실행 전 조건:
--   - 두 테이블이 sal434-04가 만든 모양이다(부모가 파티션 테이블이고 <이름>_initial이 그 파티션).
--   - 실행 계정이 두 테이블의 소유자다. 같은 세션에서 SET sal434.confirm_partition_rollback = '<오늘 한국 날짜 YYYY-MM-DD>';
-- 하는 일(한 트랜잭션, observation_batch 먼저):
--   1. 두 부모에 ACCESS EXCLUSIVE 잠금을 잡고 <이름>_initial을 떼어 낸다(DETACH).
--   2. 날짜 파티션과 기본 파티션에 들어온 줄을 id 그대로 옛 테이블로 옮긴다. source_date는 옛 테이블의 기본값
--      2000-01-01이 된다(04a의 확인 제약을 지키기 위해서다. 실제 날짜는 배치의 scheduled_at으로 다시 알 수 있다).
--      옮긴 줄 수가 날짜·기본 파티션의 줄 수와 다르면 멈춘다. 줄을 지우는 것은 옮긴 뒤 부모를 지울 때뿐이다.
--   3. 부모 이름을 비키고 옛 테이블을 원래 이름으로 되돌린 뒤, 뷰를 같은 정의로 다시 만들어 옛 테이블을 가리키게 한다.
--   4. 부모(와 날짜·기본 파티션)를 지운다. 04가 _initial을 붙인 인덱스 이름을 되돌린다.
--   5. IDENTITY를 되돌린다. 부모 시퀀스의 다음 값과 max(id)+1 가운데 큰 값부터 잇는다.
--   6. vehicle_observation → observation_batch FK(fk_observation_batch)를 NOT VALID로 다시 만든다.
--      전체 확인은 COMMIT 뒤의 VALIDATE CONSTRAINT가 한다(SHARE UPDATE EXCLUSIVE, 쓰기를 막지 않는다).
--   04a가 더한 열·확인 제약·유일 인덱스는 남긴다. 그대로 두고 04를 다시 실행할 수 있다(지우는 방법은 04a 머리 주석).
-- 잡는 잠금과 예상 시간: 두 테이블과 뷰에 ACCESS EXCLUSIVE. 날짜·기본 파티션에 들어온 줄 수에 비례한다(전환한 지 하루 안이면
--   수 초~수십 초로 본다. 추정). lock_timeout 5초, statement_timeout 15분. 마지막 VALIDATE는 관측 테이블 전체를 읽는다.
-- 여러 번 실행해도 된다. 이미 일반 테이블이면 알리고 끝난다.
-- 실행: psql -X -v ON_ERROR_STOP=1 -c "SET sal434.confirm_partition_rollback = '...'" -f 이 파일
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '15min';
SET LOCAL search_path = public;

DO $$
DECLARE
    today constant date := (now() AT TIME ZONE 'Asia/Seoul')::date;
    confirm text := nullif(current_setting('sal434.confirm_partition_rollback', true), '');
    tables constant text[] := ARRAY['observation_batch', 'vehicle_observation'];
    t text;
    initial text;
    holding text;
    kinds text;
    columns text;
    r record;
    view_defs jsonb;
    expected bigint;
    moved bigint;
    next_id bigint;
    identity_kind "char";
    seq regclass;
    seq_last bigint;
    seq_called boolean;
BEGIN
    SELECT string_agg(coalesce(relkind::text, '-'), '' ORDER BY ord) INTO kinds
      FROM unnest(tables) WITH ORDINALITY AS x(name, ord)
      LEFT JOIN pg_class ON oid = to_regclass('public.' || name);
    IF kinds = 'rr' AND to_regclass('public.observation_batch_initial') IS NULL
       AND to_regclass('public.vehicle_observation_initial') IS NULL THEN
        RAISE NOTICE 'SAL-434: 두 테이블은 이미 일반 테이블이다. 바꾸는 것 없이 끝난다';
        RETURN;
    END IF;
    IF kinds IS DISTINCT FROM 'pp' THEN
        RAISE EXCEPTION 'SAL-434: 두 테이블이 모두 파티션 테이블이어야 되돌릴 수 있다(지금 relkind %)', kinds;
    END IF;
    FOREACH t IN ARRAY tables LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_inherits WHERE inhparent = t::regclass
                       AND inhrelid = to_regclass('public.' || t || '_initial')) THEN
            RAISE EXCEPTION 'SAL-434: %_initial 가 % 의 파티션이 아니다', t, t;
        END IF;
    END LOOP;
    IF confirm IS DISTINCT FROM to_char(today, 'YYYY-MM-DD') THEN
        RAISE EXCEPTION 'SAL-434: 승인값이 없다. SET sal434.confirm_partition_rollback = ''%''; 를 넣고 다시 실행한다', to_char(today, 'YYYY-MM-DD');
    END IF;

    SELECT coalesce(jsonb_agg(jsonb_build_object('name', x.view_name, 'def', x.view_def) ORDER BY x.view_name), '[]'::jsonb)
      INTO view_defs
      FROM (SELECT DISTINCT v.oid::regclass::text AS view_name, pg_get_viewdef(v.oid) AS view_def
              FROM pg_depend dep JOIN pg_rewrite rw ON rw.oid = dep.objid JOIN pg_class v ON v.oid = rw.ev_class
             WHERE dep.classid = 'pg_rewrite'::regclass AND dep.refclassid = 'pg_class'::regclass
               AND dep.refobjid = ANY (ARRAY['observation_batch'::regclass, 'vehicle_observation'::regclass])
               AND v.oid <> dep.refobjid) x;

    LOCK TABLE observation_batch, vehicle_observation IN ACCESS EXCLUSIVE MODE;

    FOREACH t IN ARRAY tables LOOP
        initial := t || '_initial';
        holding := t || '_partitioned_rollback';

        EXECUTE format('ALTER TABLE %I DETACH PARTITION %I', t, initial);
        EXECUTE format('SELECT count(*) FROM %I', t) INTO expected;

        -- source_date를 뺀 같은 이름의 열을 옮긴다. source_date는 옛 테이블의 기본값(2000-01-01)을 받는다.
        SELECT string_agg(quote_ident(attname), ', ' ORDER BY attnum) INTO columns
          FROM pg_attribute WHERE attrelid = t::regclass AND attnum > 0 AND NOT attisdropped AND attname <> 'source_date';
        EXECUTE format('INSERT INTO %I (%s) SELECT %s FROM %I', initial, columns, columns, t);
        GET DIAGNOSTICS moved = ROW_COUNT;
        IF moved <> expected THEN
            RAISE EXCEPTION 'SAL-434: % 날짜 파티션의 줄 % 개 가운데 % 개만 옮겼다', t, expected, moved;
        END IF;
        RAISE NOTICE 'SAL-434: % 날짜·기본 파티션의 줄 % 개를 옛 테이블로 옮겼다', t, moved;

        EXECUTE format('ALTER TABLE %I RENAME TO %I', t, holding);
        EXECUTE format('ALTER TABLE %I RENAME TO %I', initial, t);
    END LOOP;

    FOR r IN SELECT value ->> 'name' AS name, value ->> 'def' AS def FROM jsonb_array_elements(view_defs) LOOP
        EXECUTE format('CREATE OR REPLACE VIEW %s AS %s', r.name, r.def);
        RAISE NOTICE 'SAL-434: 뷰 % 를 옛 테이블 위로 다시 만들었다', r.name;
    END LOOP;

    FOREACH t IN ARRAY tables LOOP
        holding := t || '_partitioned_rollback';
        SELECT attidentity INTO identity_kind FROM pg_attribute WHERE attrelid = holding::regclass AND attname = 'id';
        seq := pg_get_serial_sequence(holding, 'id')::regclass;
        EXECUTE format('SELECT last_value, is_called FROM %s', seq) INTO seq_last, seq_called;
        EXECUTE format('SELECT coalesce(max(id), 0) + 1 FROM %I', t) INTO next_id;
        next_id := greatest(next_id, CASE WHEN seq_called THEN seq_last + 1 ELSE seq_last END);

        EXECUTE format('DROP TABLE %I', holding);
        FOR r IN SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                  WHERE i.indrelid = t::regclass AND c.relname LIKE '%\_initial'
                    AND c.relname NOT IN ('ux_observation_batch_id_date_initial', 'ux_batch_attempt_date_initial',
                        'ux_vehicle_observation_id_date_initial', 'ux_observation_source_row_date_initial',
                        'ux_observation_vehicle_per_batch_date_initial') LOOP
            EXECUTE format('ALTER INDEX %I RENAME TO %I', r.relname, left(r.relname, length(r.relname) - length('_initial')));
        END LOOP;
        EXECUTE format('ALTER TABLE %I ALTER COLUMN id ADD GENERATED %s AS IDENTITY (START WITH %s)',
            t, CASE identity_kind WHEN 'a' THEN 'ALWAYS' ELSE 'BY DEFAULT' END, next_id);
    END LOOP;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'vehicle_observation'::regclass
                   AND confrelid = 'observation_batch'::regclass AND contype = 'f') THEN
        ALTER TABLE vehicle_observation ADD CONSTRAINT fk_observation_batch
            FOREIGN KEY (observation_batch_id, route_version_id) REFERENCES observation_batch (id, route_version_id) NOT VALID;
    END IF;
END $$;
COMMIT;

-- 되돌린 FK를 전체 줄에 대해 확인한다. 실패하면(옮긴 관측의 배치가 없음) FK는 NOT VALID로 남고 새 줄만 확인한다.
SET lock_timeout = '5s';
SET statement_timeout = '60min';
ALTER TABLE vehicle_observation VALIDATE CONSTRAINT fk_observation_batch;
RESET lock_timeout;
RESET statement_timeout;
