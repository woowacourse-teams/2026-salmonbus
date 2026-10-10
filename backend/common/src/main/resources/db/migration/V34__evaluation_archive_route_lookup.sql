-- 이관 기록 존재 여부를 노선별로 확인한다. 완료 원본 테이블의 백필은 없다.
CREATE INDEX ix_evaluation_archive_batch_route ON evaluation_archive_batch(route_version_id, id);
