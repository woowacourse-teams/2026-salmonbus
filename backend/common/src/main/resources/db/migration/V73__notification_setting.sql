SET LOCAL lock_timeout = '1s';
CREATE TABLE notification_setting (
    id             uuid         NOT NULL,
    member_id      bigint       NOT NULL,
    route_id       varchar(9)   NOT NULL,
    stop_id        varchar(9)   NOT NULL,
    direction      varchar(4)   NOT NULL,
    local_time     time         NOT NULL,
    weekdays       smallint     NOT NULL,
    occurrence_at  timestamptz  NOT NULL,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_notification_setting PRIMARY KEY (id),
    CONSTRAINT ux_notification_setting_target UNIQUE (member_id, route_id, stop_id, direction),
    CONSTRAINT fk_notification_setting_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ck_notification_setting_direction CHECK (direction IN ('UP', 'DOWN')),
    CONSTRAINT ck_notification_setting_weekdays CHECK (weekdays BETWEEN 1 AND 127),
    CONSTRAINT ck_notification_setting_local_time CHECK (EXTRACT(SECOND FROM local_time) = 0)
);
