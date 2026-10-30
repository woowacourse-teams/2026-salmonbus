-- 회원 삭제와 같은 트랜잭션에 저장한다. 회원 외래 키를 두면 탈퇴 시 요청도 사라진다.
CREATE TABLE analytics_deletion (
    public_id UUID PRIMARY KEY,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);
CREATE INDEX ix_analytics_deletion_attempts ON analytics_deletion (attempts, public_id);
