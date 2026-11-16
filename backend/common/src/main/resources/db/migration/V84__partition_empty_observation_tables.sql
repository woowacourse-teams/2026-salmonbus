SET LOCAL lock_timeout = '5s';

-- 관측 두 테이블(observation_batch, vehicle_observation)을 한국 날짜(source_date)로 나눈 파티션 테이블로 다시 만든다.
--
-- 두 테이블에 줄이 하나도 없을 때만 한다. 줄이 있는 DB는 아무것도 바꾸지 않고 끝낸다. 그런 DB는 backend/deploy/sql의 전환 SQL로
-- 바꾸며, 바꾸기 전 줄을 담은 첫 파티션까지 내보내 지우고 나면 이 마이그레이션이 만든 DB와 같은 스키마가 된다.
--
-- 다시 만든 테이블은 열·기본값·확인 제약·일반 인덱스·나가는 FK·권한이 그대로이고 다음이 다르다.
--   - source_date 열이 더해진다. 기본값이 줄을 넣는 순간의 한국 날짜라서 줄을 넣는 쪽은 이 열을 쓰지 않는다.
--   - 기본 키가 없다. 유일 제약에 source_date를 넣으므로 유일성은 같은 날짜 안에서만 지켜진다.
--   - vehicle_observation에서 observation_batch로 가는 FK가 없다. 두 줄의 source_date가 같다는 보장이 없어 둘 수 없다.
--   - 오늘부터 사흘 뒤까지의 날짜 파티션 <table>_pYYYYMMDD와 기본 파티션 <table>_default를 둔다. 그 뒤 날짜는 worker가 만든다.
DO $$
DECLARE
    tables CONSTANT text[] := ARRAY['observation_batch', 'vehicle_observation'];
    today CONSTANT date := (now() AT TIME ZONE 'Asia/Seoul')::date;
    plain_tables oid[];
    views jsonb;
    plain_indexes text[];
    t text;
    plain text;
    statement text;
    r record;
BEGIN
    SELECT array_agg(oid) INTO plain_tables
      FROM pg_class
     WHERE relnamespace = current_schema()::regnamespace AND relname::text = ANY (tables) AND relkind = 'r';
    IF cardinality(plain_tables) IS DISTINCT FROM 2 THEN
        RAISE NOTICE '관측 두 테이블이 일반 테이블이 아니어서 아무것도 바꾸지 않는다';
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM observation_batch) OR EXISTS (SELECT 1 FROM vehicle_observation) THEN
        RAISE NOTICE '관측 테이블에 줄이 있어 아무것도 바꾸지 않는다';
        RETURN;
    END IF;
    -- 다시 만든 테이블로 옮기지 않는 것이 붙어 있으면 손대지 않는다: 전환 SQL이 더한 source_date 열, 다른 테이블에서 오는 FK, 트리거.
    IF EXISTS (SELECT 1 FROM pg_attribute
                WHERE attrelid = ANY (plain_tables) AND attname = 'source_date' AND NOT attisdropped)
       OR EXISTS (SELECT 1 FROM pg_constraint
                   WHERE contype = 'f' AND confrelid = ANY (plain_tables) AND NOT conrelid = ANY (plain_tables))
       OR EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = ANY (plain_tables) AND NOT tgisinternal) THEN
        RAISE NOTICE '관측 테이블에 다른 테이블의 FK나 트리거, source_date 열이 있어 아무것도 바꾸지 않는다';
        RETURN;
    END IF;

    LOCK TABLE observation_batch, vehicle_observation IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM observation_batch) OR EXISTS (SELECT 1 FROM vehicle_observation) THEN
        RAISE NOTICE '관측 테이블에 줄이 있어 아무것도 바꾸지 않는다';
        RETURN;
    END IF;

    -- 뷰와 일반 인덱스는 정의를 적어 두었다가 다시 만든 테이블 위에 같은 이름으로 만든다.
    SELECT coalesce(
               jsonb_agg(jsonb_build_object('name', v.oid::regclass::text, 'definition', pg_get_viewdef(v.oid))), '[]')
      INTO views
      FROM pg_class v
     WHERE v.relkind = 'v'
       AND EXISTS (SELECT 1 FROM pg_depend d JOIN pg_rewrite w ON w.oid = d.objid
                    WHERE d.classid = 'pg_rewrite'::regclass AND w.ev_class = v.oid
                      AND d.refclassid = 'pg_class'::regclass AND d.refobjid = ANY (plain_tables));
    SELECT coalesce(array_agg(pg_get_indexdef(indexrelid) ORDER BY indexrelid::regclass::text), '{}')
      INTO plain_indexes
      FROM pg_index WHERE indrelid = ANY (plain_tables) AND NOT indisunique;

    FOREACH t IN ARRAY tables LOOP
        plain := t || '_unpartitioned';
        -- IDENTITY를 먼저 떼어 시퀀스 이름(<table>_id_seq)을 비운다.
        EXECUTE format('ALTER TABLE %I ALTER COLUMN id DROP IDENTITY', t);
        EXECUTE format('ALTER TABLE %I RENAME TO %I', t, plain);
        EXECUTE format('CREATE TABLE %I ('
            'LIKE %I INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING STORAGE INCLUDING COMMENTS,'
            ' source_date date NOT NULL DEFAULT ((now() AT TIME ZONE ''Asia/Seoul'')::date)'
            ') PARTITION BY RANGE (source_date)', t, plain);
        EXECUTE format('ALTER TABLE %I ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY', t);
        FOR r IN SELECT conname, pg_get_constraintdef(oid) AS definition FROM pg_constraint
                  WHERE conrelid = plain::regclass AND contype = 'f' AND NOT confrelid = ANY (plain_tables)
                  ORDER BY conname LOOP
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I %s', t, r.conname, r.definition);
        END LOOP;
        FOR r IN SELECT a.privilege_type, a.grantee, a.is_grantable
                   FROM pg_class c, aclexplode(c.relacl) a
                  WHERE c.oid = plain::regclass AND a.grantee <> c.relowner LOOP
            EXECUTE format('GRANT %s ON %I TO %s%s', r.privilege_type, t,
                CASE WHEN r.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(r.grantee)) END,
                CASE WHEN r.is_grantable THEN ' WITH GRANT OPTION' ELSE '' END);
        END LOOP;
    END LOOP;

    ALTER TABLE observation_batch ADD CONSTRAINT ux_observation_batch_id_date UNIQUE (id, source_date);
    ALTER TABLE observation_batch ADD CONSTRAINT ux_batch_attempt_date
        UNIQUE (route_version_id, attempt_key, source_date);
    ALTER TABLE vehicle_observation ADD CONSTRAINT ux_vehicle_observation_id_date UNIQUE (id, source_date);
    ALTER TABLE vehicle_observation ADD CONSTRAINT ux_observation_source_row_date
        UNIQUE (observation_batch_id, source_row_number, source_date);
    CREATE UNIQUE INDEX ux_observation_vehicle_per_batch_date
        ON vehicle_observation (observation_batch_id, vehicle_id, source_date) WHERE vehicle_id IS NOT NULL;
    -- V66이 NOT VALID로 둔 확인 제약이다. 줄이 있던 테이블을 바꾼 DB와 같게 부모에는 NOT VALID로 둔다(새 줄은 똑같이 검사한다).
    ALTER TABLE vehicle_observation DROP CONSTRAINT ck_observation_seat_quality_status;
    ALTER TABLE vehicle_observation ADD CONSTRAINT ck_observation_seat_quality_status CHECK (
        (reported_remaining_seats IS NULL AND seat_quality_status IS NULL)
        OR (
            reported_remaining_seats >= 0 AND reported_remaining_seats IS NOT NULL
            AND seat_quality_status IN ('REVIEW', 'OUTLIER', 'HISTORICAL') AND seat_quality_status IS NOT NULL
            AND remaining_seats IS NULL
        )
    ) NOT VALID;

    FOR r IN SELECT value ->> 'name' AS name, value ->> 'definition' AS definition FROM jsonb_array_elements(views) LOOP
        EXECUTE format('CREATE OR REPLACE VIEW %s AS %s', r.name, r.definition);
    END LOOP;
    DROP TABLE vehicle_observation_unpartitioned, observation_batch_unpartitioned;
    FOREACH statement IN ARRAY plain_indexes LOOP
        EXECUTE statement;
    END LOOP;

    FOREACH t IN ARRAY tables LOOP
        FOR day IN 0..3 LOOP
            EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                t || '_p' || to_char(today + day, 'YYYYMMDD'), t, today + day, today + day + 1);
        END LOOP;
        EXECUTE format('CREATE TABLE %I PARTITION OF %I DEFAULT', t || '_default', t);
    END LOOP;
END $$;
