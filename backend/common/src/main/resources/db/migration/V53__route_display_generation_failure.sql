-- Persist the retry budget so a restart or rediscovery cannot reset it.
ALTER TABLE route_display_generation_job
    ADD COLUMN attempt_limit integer NOT NULL DEFAULT 5,
    ADD COLUMN last_failure_code varchar(64),
    ADD COLUMN last_failure_at timestamptz,
    ADD COLUMN failed_at timestamptz;

-- Existing retry history is retained. Exhausted pending/expired work is retired by claim().
UPDATE route_display_generation_job SET attempt_limit=greatest(attempt_limit,attempts);

ALTER TABLE route_display_generation_job
    DROP CONSTRAINT route_display_generation_job_status_check,
    ADD CONSTRAINT route_display_generation_job_status_check
        CHECK (status IN ('PENDING','RUNNING','COMPLETED','SUPERSEDED','FAILED')),
    ADD CONSTRAINT route_display_generation_attempt_budget
        CHECK (attempt_limit > 0 AND attempts <= attempt_limit),
    ADD CONSTRAINT route_display_generation_failure_code
        CHECK (last_failure_code IS NULL OR last_failure_code ~ '^[A-Z0-9_]{1,64}$');
