-- 원본 백필과 삭제는 수행하지 않는다. 예약 키는 정산 재등록 방지에도 사용한다.
CREATE TABLE evaluation_archive_batch (
    id uuid PRIMARY KEY,
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    quality_revision bigint NOT NULL CHECK (quality_revision > 0),
    lease_token uuid NOT NULL,
    lease_until timestamptz NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'RESERVED' CHECK (state IN ('RESERVED', 'VERIFIED')),
    row_count integer NOT NULL CHECK (row_count BETWEEN 1 AND 100),
    manifest_sha256 varchar(64) CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(id, route_version_id),
    CHECK ((state = 'VERIFIED') = (manifest_sha256 IS NOT NULL)),
    CHECK ((state = 'VERIFIED') = (verified_at IS NOT NULL))
);
CREATE INDEX ix_evaluation_archive_resume ON evaluation_archive_batch(state, lease_until, id);

CREATE TABLE evaluation_archive_member (
    vehicle_observation_id bigint NOT NULL,
    target_stop_order integer NOT NULL,
    route_version_id bigint NOT NULL,
    batch_id uuid NOT NULL,
    original_sha256 varchar(64) NOT NULL CHECK (original_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(vehicle_observation_id, target_stop_order),
    FOREIGN KEY(batch_id, route_version_id) REFERENCES evaluation_archive_batch(id, route_version_id),
    FOREIGN KEY(vehicle_observation_id, route_version_id) REFERENCES vehicle_observation(id, route_version_id),
    FOREIGN KEY(vehicle_observation_id, target_stop_order)
        REFERENCES seat_forecast(vehicle_observation_id, target_stop_order)
);
CREATE INDEX ix_evaluation_archive_member_batch ON evaluation_archive_member(batch_id);
