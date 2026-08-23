CREATE TABLE model_deployment (
    id                          bigserial   NOT NULL,
    deployment_key              uuid        NOT NULL,
    release_id                  varchar(80) NOT NULL,
    model_key                   varchar(40) NOT NULL,
    model_version               varchar(60) NOT NULL,
    bundle_digest               char(64)    NOT NULL,
    manifest_digest             char(64)    NOT NULL,
    bundle_format_version       varchar(30) NOT NULL,
    prediction_target_version   varchar(40) NOT NULL,
    feature_contract_version    varchar(40) NOT NULL,
    label_policy_version        varchar(40) NOT NULL,
    trained_through             timestamptz NOT NULL,
    supported_scope_digest      char(64)    NOT NULL,
    state                       varchar(12) NOT NULL,
    predecessor_deployment_id   bigint,
    activated_at                timestamptz,
    retired_at                  timestamptz,
    CONSTRAINT pk_model_deployment PRIMARY KEY (id),
    CONSTRAINT ux_deployment_key UNIQUE (deployment_key),
    CONSTRAINT fk_deployment_predecessor FOREIGN KEY (predecessor_deployment_id)
        REFERENCES model_deployment (id),
    CONSTRAINT ck_deployment_state CHECK (state IN ('STAGED', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_deployment_state_timestamps CHECK (
        (state = 'STAGED'  AND activated_at IS NULL     AND retired_at IS NULL)
        OR (state = 'ACTIVE'  AND activated_at IS NOT NULL AND retired_at IS NULL)
        OR (state = 'RETIRED' AND activated_at IS NOT NULL AND retired_at IS NOT NULL)
    ),
    CONSTRAINT ck_deployment_retired_after_activated CHECK (retired_at IS NULL OR activated_at < retired_at)
);

CREATE UNIQUE INDEX ux_deployment_single_active
    ON model_deployment ((state))
    WHERE state = 'ACTIVE';
