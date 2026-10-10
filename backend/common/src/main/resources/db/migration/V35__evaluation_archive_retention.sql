-- 파일 검증 상태와 DB 원본의 위치를 구분한다. 기존 원본을 삭제하지 않는다.
ALTER TABLE evaluation_archive_batch
    ADD COLUMN storage_state varchar(16) NOT NULL DEFAULT 'LIVE' CHECK (storage_state IN ('LIVE','PURGED')),
    ADD COLUMN storage_revision bigint NOT NULL DEFAULT 0 CHECK (storage_revision >= 0),
    ADD COLUMN purged_at timestamptz,
    ADD COLUMN restored_at timestamptz,
    ADD CONSTRAINT ck_archive_purged_verified CHECK (storage_state <> 'PURGED' OR state = 'VERIFIED'),
    ADD CONSTRAINT ck_archive_purge_receipt CHECK (storage_state <> 'PURGED' OR purged_at IS NOT NULL);
