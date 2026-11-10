SET LOCAL lock_timeout = '1s';
CREATE TABLE notification_delivery (
    id               bigint       GENERATED ALWAYS AS IDENTITY,
    setting_id       uuid         NOT NULL,
    occurrence_at    timestamptz  NOT NULL,
    device_id        bigint       NOT NULL,
    status           varchar(10)  NOT NULL,
    attempts         smallint     NOT NULL,
    next_attempt_at  timestamptz  NOT NULL,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT pk_notification_delivery PRIMARY KEY (id),
    CONSTRAINT ux_notification_delivery_occurrence UNIQUE (setting_id, occurrence_at, device_id),
    CONSTRAINT fk_notification_delivery_setting FOREIGN KEY (setting_id) REFERENCES notification_setting (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_delivery_device FOREIGN KEY (device_id) REFERENCES push_device (id) ON DELETE CASCADE,
    CONSTRAINT ck_notification_delivery_status CHECK (status IN ('PENDING', 'SENT', 'GAVE_UP'))
);
CREATE INDEX ix_notification_delivery_due ON notification_delivery (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_notification_delivery_device ON notification_delivery (device_id);
CREATE INDEX ix_notification_setting_occurrence ON notification_setting (occurrence_at);
