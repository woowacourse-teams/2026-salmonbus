ALTER TABLE evaluation_archive_batch ADD COLUMN abandoned_at timestamptz;
CREATE INDEX ix_evaluation_archive_live_retry ON evaluation_archive_batch(lease_until,id)
    WHERE storage_state='LIVE' AND abandoned_at IS NULL;

CREATE TABLE evaluation_archive_scan (
    id integer PRIMARY KEY CHECK(id=1),
    observation_id bigint NOT NULL DEFAULT 0,
    stop_order integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT '-infinity'
);
INSERT INTO evaluation_archive_scan(id) VALUES(1);
