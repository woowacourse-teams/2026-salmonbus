-- ETA 작업 상태는 단일 수집 Worker의 메모리에서 관리한다. 여기에는 정상 수신 결과만 남긴다.
SET LOCAL lock_timeout = '1s';

CREATE TABLE arrival_snapshot (
    id uuid PRIMARY KEY,
    provider varchar(30) NOT NULL,
    operation varchar(60) NOT NULL,
    station_id varchar(20) NOT NULL,
    received_at timestamptz NOT NULL,
    refresh_after_at timestamptz NOT NULL,
    stale_at timestamptz NOT NULL,
    normalization_version varchar(40) NOT NULL,
    payload_digest char(64) NOT NULL,
    payload jsonb NOT NULL,
    stored_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (received_at <= refresh_after_at AND refresh_after_at < stale_at),
    CHECK (jsonb_typeof(payload) = 'object'),
    CHECK (payload_digest ~ '^[0-9a-f]{64}$')
);

-- source key는 노선/방문/인증키와 무관하다. 다른 노선의 응답도 같은 수신 결과를 공유한다.
CREATE INDEX ix_arrival_snapshot_latest
    ON arrival_snapshot(provider, operation, station_id, received_at DESC, stored_at DESC);
CREATE INDEX ix_arrival_snapshot_expiry ON arrival_snapshot(stale_at);
CREATE INDEX ix_route_stop_station ON route_stop(stop_id, route_version_id, stop_order);
