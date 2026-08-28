-- 하루 호출 한도를 예약으로 쓰는 티켓이 장부와 결말의 값 범위(CHECK)를 같이 조인다. SAL-85.
-- V1__collector.sql 2번 줄이 그 목록에 SAL-85 를 적어둔 몫이다.
--
-- V1 은 고치지 않는다. Flyway 의 checksum 은 주석까지 포함해 계산해서, 한 글자만 바꿔도
-- 그 파일을 이미 돌린 DB 가 기동을 거부한다.

-- 자리를 잡는 시점에는 아직 안 보내서 보낸 시각을 모른다.
-- 묶음 행은 예약할 때 열리고, 보내기 직전에 이 값을 채운다.
-- 프로세스가 그 사이에 죽으면 비어 있는 채로 남고, 그것이 "예약했는데 안 보냈다"는 증거다.
ALTER TABLE observation_batch
    ALTER COLUMN requested_at DROP NOT NULL;

ALTER TABLE observation_batch
    -- 결말은 성공 · 실패만이 아니라 예약 사다리의 위치까지 담는다.
    -- 자리를 잡았는데 안 보낸 판과 보냈는데 결과를 모르는 판을 뭉치면 재시도가 한도를 두 번 쓴다.
    ADD CONSTRAINT ck_batch_outcome_value
        CHECK (outcome IN (
            'RESERVED',
            'DISPATCHING',
            'NOT_RESERVED',
            'ABANDONED_BEFORE_SEND',
            'UNKNOWN_AFTER_DISPATCH',
            'SUCCESS_ROWS',
            'SUCCESS_EMPTY',
            'FAILED_UPSTREAM',
            'FAILED_UNREADABLE')),
    -- 결말 하나로 사건이 특정되는 판은 사유가 비어 있다. 응답이 안 온 판 · 응답을 못 읽은 판 ·
    -- 보내기 전에 그만둔 판이 그렇다.
    ADD CONSTRAINT ck_batch_failure_code_value
        CHECK (failure_code IN (
            'LOCAL_QUOTA_EXHAUSTED',
            'DAILY_QUOTA_EXCEEDED',
            'PER_SECOND_QUOTA_EXCEEDED',
            'UPSTREAM_ERROR'));

ALTER TABLE daily_call_quota
    -- 자리를 잡는 것은 reserved_calls < daily_limit 을 조건으로 단 UPDATE 한 문장이라
    -- 한도를 넘길 수 없다. 그 조건이 코드에서 빠지면 여기서 막힌다.
    ADD CONSTRAINT ck_quota_reserved_within_limit
        CHECK (reserved_calls BETWEEN 0 AND daily_limit),
    -- 한도가 0 인 장부는 예약을 하나도 못 받는다. 설정을 안 넣은 것이지 한도가 0 인 것이 아니다.
    ADD CONSTRAINT ck_quota_daily_limit_positive
        CHECK (daily_limit > 0);
