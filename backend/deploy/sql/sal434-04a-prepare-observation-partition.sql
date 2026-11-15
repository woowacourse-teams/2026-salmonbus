-- SAL-434 관측 두 테이블 날짜 나눔의 준비 단계. 자동 실행하지 않는다.
--
-- 언제: RUNBOOK "SAL-434 전환 절차" 8번 차례. sal434-03-drop-legacy-tables.sql을 실행한 뒤,
--       sal434-04-partition-observation-tables.sql보다 먼저 실행한다. 쓰기가 뜸한 시간(02~05시)에 한다.
-- 실행 전 조건:
--   - 옛 이관 앱(salmonbus-archive의 노선 판본 이관)이 멈춰 있다. 이 파일이 열을 더하면 옛 이관의
--     줄 비교 삭제(to_jsonb(t)=원본)가 맞지 않아 실패한다. 지워지지는 않지만 실패가 쌓인다.
--   - RDS EBS 처리량 크레딧(최근 1시간 최소 20% 이상)과 여유 저장공간(아래 인덱스 크기의 두 배 이상)을 확인했다.
--     같은 열 구성의 기존 유일 인덱스 크기는 sal434-01-precheck.sql의 6-2가 보인다.
-- 하는 일:
--   1. 두 테이블에 source_date date NOT NULL DEFAULT DATE '2000-01-01'을 더한다. 상수 기본값이라 테이블을 다시 쓰지 않는다.
--   2. "source_date = 2000-01-01" 확인 제약을 NOT VALID로 더하고 VALIDATE한다. 04의 ATTACH PARTITION이 이 제약을 보고
--      테이블 전체 읽기를 건너뛴다(PostgreSQL 18에서 확인).
--   3. 04가 파티션 부모의 유일 제약으로 붙일 유일 인덱스를 CONCURRENTLY로 미리 만든다(파티션 키 source_date 포함).
-- 잡는 잠금과 예상 시간:
--   - ADD COLUMN, ADD CONSTRAINT NOT VALID: ACCESS EXCLUSIVE, 카탈로그만 바꾼다(로컬 100만 줄에서 수 ms). lock_timeout 5초.
--   - VALIDATE CONSTRAINT: SHARE UPDATE EXCLUSIVE(읽기·쓰기를 막지 않는다), 테이블 전체를 한 번 읽는다.
--     로컬 100만 줄(231MB)에서 0.1초. 운영 관측 테이블은 크레딧이 있으면 1분 안팎으로 본다(추정).
--   - CREATE UNIQUE INDEX CONCURRENTLY: 쓰기를 막지 않는다. 인덱스마다 테이블을 두 번 읽는다.
--     로컬 100만 줄에서 인덱스 하나 0.3초. 운영은 인덱스마다 수 분으로 본다(추정, dev 리허설로 잰다).
-- 여러 번 실행해도 된다. 이미 있는 열·제약·인덱스는 건너뛴다. 04가 끝난 뒤 실행하면 CONCURRENTLY 문장에서
-- "파티션 테이블에는 동시에 만들 수 없다" 오류로 멈추지만, 그 앞의 문장은 모두 건너뛰므로 바뀌는 것은 없다.
-- 실패하면: CONCURRENTLY가 중간에 실패하면 유효하지 않은 인덱스가 남고 IF NOT EXISTS는 그것을 고치지 않는다.
--   마지막 SELECT의 valid가 false인 인덱스는 DROP INDEX CONCURRENTLY <이름>; 으로 지운 뒤 이 파일을 다시 실행한다.
-- 되돌리기(04를 실행하기 전이거나 sal434-04-rollback으로 04를 되돌린 뒤에만): 아래를 차례로 실행한다.
--   인덱스는 04 전에는 일반 인덱스, 04를 되돌린 뒤에는 제약이 붙은 인덱스다. 둘 다 처리하도록 제약을 먼저 지운다.
--   ALTER TABLE observation_batch DROP CONSTRAINT IF EXISTS ux_observation_batch_id_date_legacy;
--   ALTER TABLE observation_batch DROP CONSTRAINT IF EXISTS ux_batch_attempt_date_legacy;
--   ALTER TABLE vehicle_observation DROP CONSTRAINT IF EXISTS ux_vehicle_observation_id_date_legacy;
--   ALTER TABLE vehicle_observation DROP CONSTRAINT IF EXISTS ux_observation_source_row_date_legacy;
--   DROP INDEX CONCURRENTLY IF EXISTS ux_observation_batch_id_date_legacy;
--   DROP INDEX CONCURRENTLY IF EXISTS ux_batch_attempt_date_legacy;
--   DROP INDEX CONCURRENTLY IF EXISTS ux_vehicle_observation_id_date_legacy;
--   DROP INDEX CONCURRENTLY IF EXISTS ux_observation_source_row_date_legacy;
--   DROP INDEX CONCURRENTLY IF EXISTS ux_observation_vehicle_per_batch_date_legacy;
--   ALTER TABLE observation_batch DROP CONSTRAINT IF EXISTS ck_observation_batch_legacy_date;
--   ALTER TABLE vehicle_observation DROP CONSTRAINT IF EXISTS ck_vehicle_observation_legacy_date;
--   ALTER TABLE observation_batch DROP COLUMN IF EXISTS source_date;      -- 카탈로그에서만 지워진다(다시 쓰지 않는다)
--   ALTER TABLE vehicle_observation DROP COLUMN IF EXISTS source_date;
-- 실행: psql -X -v ON_ERROR_STOP=1 -f sal434-04a-prepare-observation-partition.sql (트랜잭션으로 감싸지 않는다)
SET lock_timeout = '5s';
SET statement_timeout = '60min';
SET search_path = public;

DO $$
DECLARE
    t text;
    kind "char";
    column_ok boolean;
BEGIN
    FOREACH t IN ARRAY ARRAY['observation_batch', 'vehicle_observation'] LOOP
        SELECT relkind INTO kind FROM pg_class WHERE oid = to_regclass('public.' || t);
        IF kind IS NULL THEN
            RAISE EXCEPTION 'SAL-434: % 테이블이 없다', t;
        END IF;
        IF kind = 'p' THEN
            RAISE NOTICE 'SAL-434: % 는 이미 파티션 테이블이다. 바꾸는 것 없이 끝난다', t;
            CONTINUE;
        END IF;
        SELECT a.atttypid = 'date'::regtype AND a.attnotnull
               AND pg_get_expr(d.adbin, d.adrelid) = '''2000-01-01''::date'
          INTO column_ok
          FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
         WHERE a.attrelid = t::regclass AND a.attname = 'source_date' AND NOT a.attisdropped;
        IF column_ok IS FALSE THEN
            RAISE EXCEPTION 'SAL-434: %.source_date 가 이미 있지만 date NOT NULL DEFAULT 2000-01-01 이 아니다', t;
        END IF;
    END LOOP;
END $$;

ALTER TABLE observation_batch ADD COLUMN IF NOT EXISTS source_date date NOT NULL DEFAULT DATE '2000-01-01';
ALTER TABLE vehicle_observation ADD COLUMN IF NOT EXISTS source_date date NOT NULL DEFAULT DATE '2000-01-01';

-- 확인 제약은 IF NOT EXISTS가 없어 조건부로 만든다. 파티션 전환 뒤에는 옛 파티션에만 있으므로 건너뛴다.
DO $$
DECLARE
    t text;
    c text;
BEGIN
    FOREACH t IN ARRAY ARRAY['observation_batch', 'vehicle_observation'] LOOP
        IF (SELECT relkind FROM pg_class WHERE oid = t::regclass) <> 'r' THEN
            CONTINUE;
        END IF;
        c := 'ck_' || t || '_legacy_date';
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = t::regclass AND conname = c) THEN
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (source_date = DATE ''2000-01-01'') NOT VALID', t, c);
        END IF;
    END LOOP;
END $$;
-- VALIDATE는 별도 트랜잭션에서 SHARE UPDATE EXCLUSIVE만 잡는다. 이미 확인된 제약이면 바로 끝난다.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['observation_batch', 'vehicle_observation'] LOOP
        IF (SELECT relkind FROM pg_class WHERE oid = t::regclass) = 'r' THEN
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', t, 'ck_' || t || '_legacy_date');
        END IF;
    END LOOP;
END $$;

-- 04가 옛 테이블을 붙일 때 부모의 유일 제약에 연결할 인덱스다. 이름의 _legacy는 옛 파티션의 인덱스라는 뜻이다.
-- 열 순서는 지금 조회(id 하나, 노선 판본+시도 키, 배치+줄 번호, 배치+차량)가 앞 열로 쓸 수 있게 둔다.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_observation_batch_id_date_legacy
    ON observation_batch (id, source_date);
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_batch_attempt_date_legacy
    ON observation_batch (route_version_id, attempt_key, source_date);
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_vehicle_observation_id_date_legacy
    ON vehicle_observation (id, source_date);
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_observation_source_row_date_legacy
    ON vehicle_observation (observation_batch_id, source_row_number, source_date);
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_observation_vehicle_per_batch_date_legacy
    ON vehicle_observation (observation_batch_id, vehicle_id, source_date) WHERE vehicle_id IS NOT NULL;

-- 결과 확인. valid·ready가 모두 true여야 04를 실행할 수 있다.
SELECT c.relname AS index_name, i.indrelid::regclass AS table_name, i.indisvalid AS valid, i.indisready AS ready,
       pg_size_pretty(pg_relation_size(c.oid)) AS size
  FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
 WHERE c.relname IN ('ux_observation_batch_id_date_legacy', 'ux_batch_attempt_date_legacy',
                     'ux_vehicle_observation_id_date_legacy', 'ux_observation_source_row_date_legacy',
                     'ux_observation_vehicle_per_batch_date_legacy')
 ORDER BY 1;
SELECT conrelid::regclass AS table_name, conname, convalidated
  FROM pg_constraint
 WHERE conname IN ('ck_observation_batch_legacy_date', 'ck_vehicle_observation_legacy_date')
 ORDER BY 1;

RESET lock_timeout;
RESET statement_timeout;
RESET search_path;
