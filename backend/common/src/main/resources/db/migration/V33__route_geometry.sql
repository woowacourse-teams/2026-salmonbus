-- 형상은 정상 변경 시에만 새 행을 남긴다. 정류장 방문 판본과 별도로 변경될 수 있다.
SET LOCAL lock_timeout = '1s';
CREATE TABLE route_geometry (
    id uuid PRIMARY KEY,
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    received_at timestamptz NOT NULL,
    content_digest char(64) NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    points jsonb NOT NULL CHECK (jsonb_typeof(points) = 'array' AND jsonb_array_length(points) >= 2)
);
CREATE INDEX ix_route_geometry_latest ON route_geometry(route_version_id, received_at DESC, id DESC);
