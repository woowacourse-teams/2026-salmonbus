SET LOCAL lock_timeout = '1s';
CREATE TABLE push_device (
    id                  bigint       GENERATED ALWAYS AS IDENTITY,
    member_id           bigint       NOT NULL,
    token               varchar(200) NOT NULL,
    environment         varchar(10)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    last_registered_at  timestamptz  NOT NULL,
    CONSTRAINT pk_push_device PRIMARY KEY (id),
    CONSTRAINT ux_push_device_token UNIQUE (token),
    CONSTRAINT fk_push_device_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ck_push_device_environment CHECK (environment IN ('SANDBOX', 'PRODUCTION'))
);
CREATE INDEX ix_push_device_member ON push_device (member_id);
