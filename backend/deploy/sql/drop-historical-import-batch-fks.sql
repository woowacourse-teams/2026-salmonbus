-- 자동 실행하지 않는다. 별도 승인을 받고, RUNBOOK "옛 이관 기록 테이블의 FK 정리"의 내보내기·재읽기 확인을 마친 뒤 실행한다.
-- historical_import_record, migration_source_record 가 observation_batch 를 참조하는 FK 제약만 지운다.
-- 두 테이블과 행, observation_batch 가 historical_import_batch 를 참조하는 FK 는 그대로 둔다. 테이블이 없으면 아무것도 바꾸지 않는다.
-- ALTER TABLE 은 두 테이블과 observation_batch 에 잠금을 잡으므로 Worker·이관 앱을 멈춘 시간에 실행한다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN
        SELECT con.conrelid::regclass AS table_name, con.conname
        FROM pg_constraint con
        WHERE con.contype = 'f'
          AND con.confrelid = 'observation_batch'::regclass
          AND con.conrelid IN (
              SELECT to_regclass(name)
              FROM unnest(ARRAY['historical_import_record', 'migration_source_record']) AS name
              WHERE to_regclass(name) IS NOT NULL)
    LOOP
        RAISE NOTICE 'drop % on %', c.conname, c.table_name;
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', c.table_name, c.conname);
    END LOOP;
END $$;
-- 남은 observation_batch 참조 FK 를 확인한다. 두 테이블의 제약이 없어야 한다.
SELECT con.conrelid::regclass AS referencing_table, con.conname
FROM pg_constraint con
WHERE con.contype = 'f' AND con.confrelid = 'observation_batch'::regclass
ORDER BY 1, 2;
COMMIT;
