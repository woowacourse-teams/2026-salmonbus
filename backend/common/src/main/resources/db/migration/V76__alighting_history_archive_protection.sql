CREATE TABLE alighting_history_checkpoint (
    route_version_id bigint PRIMARY KEY REFERENCES route_version(id),
    through_at timestamptz NOT NULL,
    through_batch_id bigint NOT NULL,
    retain_from timestamptz NOT NULL
);
