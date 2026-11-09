-- 원본이나 관측 근거가 달라진 검증 묶음은 보존하고 자동 삭제 대상에서 제외한다.
ALTER TABLE les_evaluation_archive ADD COLUMN abandoned_at timestamptz;
CREATE INDEX idx_les_archive_purge_candidate ON les_evaluation_archive(verified_at)
    WHERE storage_state='LIVE' AND restored_at IS NULL AND abandoned_at IS NULL;
