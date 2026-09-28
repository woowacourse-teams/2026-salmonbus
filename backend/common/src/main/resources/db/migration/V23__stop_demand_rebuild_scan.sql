-- 기존 진행 행은 이 표가 없어 기존 관측 ID 순회로 마친다. 새 작업부터 노선별 묶음을 순회한다.
-- 새 SCAN 상태는 구버전 worker가 해석할 수 없다. 진행 중 구버전 롤백에는 별도 복구가 필요하다.
CREATE TABLE stop_demand_rebuild_scan (
    route_version_id bigint NOT NULL,
    vehicle_id varchar(40) NOT NULL,
    batch_until_id bigint NOT NULL,
    after_at timestamptz,
    after_batch_id bigint NOT NULL DEFAULT 0,
    group_end_at timestamptz,
    group_end_id bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (route_version_id, vehicle_id),
    FOREIGN KEY (route_version_id, vehicle_id)
        REFERENCES stop_demand_rebuild_progress(route_version_id, vehicle_id) ON DELETE CASCADE
);
