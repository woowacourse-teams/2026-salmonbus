SET LOCAL lock_timeout = '1s';
DELETE FROM push_device;

ALTER TABLE push_device
    ADD COLUMN session_family_id uuid NOT NULL,
    ADD CONSTRAINT fk_push_device_session FOREIGN KEY (session_family_id)
        REFERENCES refresh_token (family_id) ON DELETE CASCADE;

CREATE INDEX ix_push_device_session ON push_device (session_family_id);
