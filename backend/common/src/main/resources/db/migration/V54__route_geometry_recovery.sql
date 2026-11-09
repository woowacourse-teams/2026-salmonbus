CREATE TABLE route_geometry_recovery (
    route_version_id bigint PRIMARY KEY REFERENCES route_version(id) ON DELETE CASCADE,
    route_id bigint NOT NULL REFERENCES route(id) ON DELETE CASCADE,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','EXHAUSTED','SUPERSEDED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid,
    lease_until timestamptz,
    last_outcome varchar(40),
    cycle_started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((status='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX route_geometry_recovery_due ON route_geometry_recovery(next_attempt_at)
    WHERE status IN ('PENDING','RUNNING');
