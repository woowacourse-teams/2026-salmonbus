-- 기존 묶음과 원본은 이동하거나 삭제하지 않는다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE evaluation_archive_batch DROP CONSTRAINT evaluation_archive_batch_row_count_check;
-- 기존 1~100행은 새 범위에도 포함된다. 이력 전체 검사 없이 새 쓰기부터 적용한다.
ALTER TABLE evaluation_archive_batch ADD CONSTRAINT evaluation_archive_batch_row_count_check
    CHECK (row_count BETWEEN 1 AND 1000) NOT VALID;
