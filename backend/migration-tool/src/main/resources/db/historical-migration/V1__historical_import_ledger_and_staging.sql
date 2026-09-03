-- S3 원문 자체는 저장하지 않는다. 이 스키마는 검증된 정규화 archive의 provenance,
-- 재개 지점, 임시 staging, 최종 관측과의 연결만 가진다.

CREATE TABLE historical_import_batch (
    id                              uuid         NOT NULL,
    manifest_sha256                 varchar(64)  NOT NULL,
    archive_schema_version          varchar(60)  NOT NULL,
    archive_kind                    varchar(20)  NOT NULL,
    previous_manifest_sha256        varchar(64),
    terminal_freeze_receipt_sha256  varchar(64),
    inventory_sha256                varchar(64)  NOT NULL,
    source_cutoff_at                timestamptz  NOT NULL,
    source_collected_from           timestamptz,
    source_collected_through        timestamptz,
    importer_version                varchar(60)  NOT NULL,
    target_kind                     varchar(12)  NOT NULL,
    target_authority_from_min       timestamptz  NOT NULL,
    route_validity_policy           varchar(40)  NOT NULL,
    status                          varchar(20)  NOT NULL,
    expected_batch_count            bigint       NOT NULL,
    expected_observation_count      bigint       NOT NULL,
    staged_batch_count              bigint       NOT NULL DEFAULT 0,
    staged_observation_count        bigint       NOT NULL DEFAULT 0,
    inserted_batch_count            bigint       NOT NULL DEFAULT 0,
    inserted_observation_count      bigint       NOT NULL DEFAULT 0,
    duplicate_batch_count           bigint       NOT NULL DEFAULT 0,
    rejected_batch_count            bigint       NOT NULL DEFAULT 0,
    baseline_observation_batch_count bigint,
    baseline_vehicle_observation_count bigint,
    baseline_seat_forecast_count    bigint,
    baseline_statistics_count       bigint,
    baseline_model_deployment_count bigint,
    baseline_response_from          timestamptz,
    baseline_response_through       timestamptz,
    created_at                      timestamptz  NOT NULL DEFAULT now(),
    updated_at                      timestamptz  NOT NULL DEFAULT now(),
    completed_at                    timestamptz,
    rolled_back_at                  timestamptz,
    reconciliation_receipt          jsonb,
    CONSTRAINT pk_historical_import_batch PRIMARY KEY (id),
    CONSTRAINT ux_historical_import_manifest UNIQUE (manifest_sha256),
    CONSTRAINT ck_historical_import_manifest_digest CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_historical_import_previous_digest CHECK (
        previous_manifest_sha256 IS NULL OR previous_manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_historical_import_terminal_receipt_digest CHECK (
        terminal_freeze_receipt_sha256 IS NULL
        OR terminal_freeze_receipt_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_historical_import_inventory_digest CHECK (inventory_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_historical_import_archive_kind CHECK (archive_kind IN ('BASE', 'LATE_DELTA', 'TERMINAL_DELTA')),
    CONSTRAINT ck_historical_import_terminal_freeze CHECK (
        (archive_kind = 'TERMINAL_DELTA') = (terminal_freeze_receipt_sha256 IS NOT NULL)),
    CONSTRAINT ck_historical_import_target_kind CHECK (target_kind IN ('LOCAL', 'ACADEMY')),
    CONSTRAINT ck_historical_import_status CHECK (
        status IN ('STAGING', 'STAGED', 'VALIDATED', 'MERGING', 'COMPLETE', 'FAILED', 'ROLLED_BACK')),
    CONSTRAINT ck_historical_import_counts CHECK (
        expected_batch_count >= 0
        AND expected_observation_count >= 0
        AND staged_batch_count >= 0
        AND staged_observation_count >= 0
        AND inserted_batch_count >= 0
        AND inserted_observation_count >= 0
        AND duplicate_batch_count >= 0
        AND rejected_batch_count >= 0),
    CONSTRAINT ck_historical_import_source_range CHECK (
        source_collected_from IS NULL
        OR source_collected_through IS NULL
        OR source_collected_from <= source_collected_through)
);

CREATE TABLE historical_import_dataset_seal (
    terminal_manifest_sha256        varchar(64)  NOT NULL,
    terminal_freeze_receipt_sha256  varchar(64)  NOT NULL,
    terminal_import_batch_id        uuid         NOT NULL,
    sealed_at                       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_historical_import_dataset_seal PRIMARY KEY (terminal_manifest_sha256),
    CONSTRAINT ux_historical_import_dataset_terminal_receipt UNIQUE (terminal_freeze_receipt_sha256),
    CONSTRAINT ux_historical_import_dataset_terminal_batch UNIQUE (terminal_import_batch_id),
    CONSTRAINT fk_historical_import_dataset_terminal_batch FOREIGN KEY (terminal_import_batch_id)
        REFERENCES historical_import_batch (id),
    CONSTRAINT ck_historical_import_dataset_manifest CHECK (terminal_manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_historical_import_dataset_receipt CHECK (terminal_freeze_receipt_sha256 ~ '^[0-9a-f]{64}$')
);

-- 교체 경계 장부다. FINAL_CUTOVER_AT(paused_at)과 observation high-water를 한 transaction에 고정해
-- freeze/cleanup/seed/unpause가 같은 경계를 읽게 한다. worker는 이 표를 읽지 않는다.
CREATE TABLE forecast_cutover_control (
    singleton                       boolean      NOT NULL DEFAULT true,
    writes_paused                   boolean      NOT NULL DEFAULT false,
    cutover_id                      uuid,
    pause_reason                    varchar(80),
    paused_at                       timestamptz,
    updated_at                      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_forecast_cutover_control PRIMARY KEY (singleton),
    CONSTRAINT ck_forecast_cutover_singleton CHECK (singleton),
    CONSTRAINT ck_forecast_cutover_pause_fields CHECK (
        (writes_paused AND cutover_id IS NOT NULL AND pause_reason IS NOT NULL AND paused_at IS NOT NULL)
        OR
        (NOT writes_paused AND cutover_id IS NULL AND pause_reason IS NULL AND paused_at IS NULL))
);

INSERT INTO forecast_cutover_control (singleton) VALUES (true);

CREATE TABLE historical_import_route_boundary (
    import_batch_id                 uuid         NOT NULL,
    model_route                     varchar(20)  NOT NULL,
    target_authority_from           timestamptz  NOT NULL,
    accepted_source_from            timestamptz,
    accepted_source_through         timestamptz,
    overlap_batch_count             bigint       NOT NULL DEFAULT 0,
    overlap_observation_count       bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_historical_import_route_boundary PRIMARY KEY (import_batch_id, model_route),
    CONSTRAINT fk_historical_import_route_boundary_batch FOREIGN KEY (import_batch_id)
        REFERENCES historical_import_batch (id),
    CONSTRAINT ck_historical_import_route_boundary_route CHECK (model_route IN ('1650', '3330')),
    CONSTRAINT ck_historical_import_route_boundary_range CHECK (
        accepted_source_from IS NULL OR accepted_source_through IS NULL
        OR accepted_source_from <= accepted_source_through),
    CONSTRAINT ck_historical_import_route_boundary_counts CHECK (
        overlap_batch_count >= 0 AND overlap_observation_count >= 0)
);

-- 품질 검증용이 아닌 model release가 observation 원천까지 오염시키지는 않는다.
-- trainer와 historical cell seed builder만 이 장부를 model_deployment에 anti-join한다.
CREATE TABLE training_model_release_exclusion (
    release_id                      varchar(80)  NOT NULL,
    bundle_digest                   varchar(64)  NOT NULL,
    observed_model_deployment_id    bigint,
    calculation_version             varchar(40)  NOT NULL,
    observed_activated_at           timestamptz,
    final_cutover_at                timestamptz,
    statistics_baseline_count       bigint       NOT NULL,
    statistics_baseline_not_after   timestamptz  NOT NULL,
    classification                  varchar(24)  NOT NULL,
    decision_reference              varchar(120) NOT NULL,
    recorded_at                     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_training_model_release_exclusion PRIMARY KEY (release_id, bundle_digest),
    CONSTRAINT ck_training_model_release_exclusion_digest CHECK (bundle_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ux_training_model_release_exclusion_calculation UNIQUE (calculation_version),
    CONSTRAINT ck_training_model_release_exclusion_window CHECK (
        final_cutover_at IS NULL OR final_cutover_at > observed_activated_at),
    CONSTRAINT ck_training_model_release_exclusion_baseline CHECK (statistics_baseline_count >= 0),
    CONSTRAINT ck_training_model_release_exclusion_classification CHECK (
        classification IN ('TEMPORARY_RELEASE', 'INVALID_RELEASE', 'OPERATOR_EXCLUDED'))
);

INSERT INTO training_model_release_exclusion (
    release_id, bundle_digest, observed_model_deployment_id, calculation_version,
    observed_activated_at, final_cutover_at, statistics_baseline_count,
    statistics_baseline_not_after, classification, decision_reference)
VALUES (
    'salmonbus-d57370be9195520e',
    'd57370be9195520ecf3b0ef125aa3611090ed5f41ade2963c33f38d99a29e89a',
    1,
    'seat-feature-contract-v4-1-2026-09-02',
    '2026-09-02T11:55:04.729493Z',
    NULL,
    0,
    '2026-09-02T11:55:04.729493Z',
    'TEMPORARY_RELEASE',
    '2026-09-02 operator decision');

CREATE TABLE temporary_statistics_generation_freeze (
    id                              uuid         NOT NULL,
    release_id                      varchar(80)  NOT NULL,
    bundle_digest                   varchar(64)  NOT NULL,
    cutover_at                      timestamptz  NOT NULL,
    manifest_sha256                 varchar(64)  NOT NULL,
    generation_count                integer      NOT NULL,
    row_count                       bigint       NOT NULL,
    status                          varchar(16)  NOT NULL,
    frozen_at                       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_temporary_statistics_generation_freeze PRIMARY KEY (id),
    CONSTRAINT ux_temporary_statistics_generation_manifest UNIQUE (manifest_sha256),
    CONSTRAINT fk_temporary_statistics_generation_release FOREIGN KEY (release_id, bundle_digest)
        REFERENCES training_model_release_exclusion (release_id, bundle_digest),
    CONSTRAINT ck_temporary_statistics_generation_manifest CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_temporary_statistics_generation_counts CHECK (generation_count >= 0 AND row_count >= 0),
    CONSTRAINT ck_temporary_statistics_generation_status CHECK (status IN ('FROZEN', 'CLEANED'))
);

CREATE TABLE training_statistics_generation_exclusion (
    freeze_id                       uuid         NOT NULL,
    release_id                      varchar(80)  NOT NULL,
    bundle_digest                   varchar(64)  NOT NULL,
    route_version_id                bigint       NOT NULL,
    calculation_version             varchar(40)  NOT NULL,
    revision                        integer      NOT NULL,
    data_until                      timestamptz  NOT NULL,
    computed_at                     timestamptz  NOT NULL,
    frozen_cell_count               integer      NOT NULL,
    frozen_at                       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_training_statistics_generation_exclusion PRIMARY KEY (
        freeze_id, route_version_id, calculation_version,
        revision, data_until, computed_at),
    CONSTRAINT fk_training_statistics_generation_freeze FOREIGN KEY (freeze_id)
        REFERENCES temporary_statistics_generation_freeze (id),
    CONSTRAINT fk_training_statistics_generation_release FOREIGN KEY (release_id, bundle_digest)
        REFERENCES training_model_release_exclusion (release_id, bundle_digest),
    CONSTRAINT fk_training_statistics_generation_route FOREIGN KEY (route_version_id)
        REFERENCES route_version (id),
    CONSTRAINT ck_training_statistics_generation_revision CHECK (revision >= 1),
    CONSTRAINT ck_training_statistics_generation_cells CHECK (frozen_cell_count >= 1)
);

-- trainer와 cell seed builder는 seat_forecast를 직접 읽지 않고 이 view를 source로 삼는다.
-- observation 자체에는 exclusion을 전파하지 않는다.
CREATE VIEW training_eligible_seat_forecast AS
SELECT forecast.*
FROM seat_forecast forecast
JOIN model_deployment deployment
  ON deployment.id = forecast.model_deployment_id
WHERE NOT EXISTS (
    SELECT 1
    FROM training_model_release_exclusion excluded
    WHERE excluded.observed_model_deployment_id = deployment.id
      AND excluded.release_id = deployment.release_id
      AND excluded.bundle_digest = deployment.bundle_digest
      AND excluded.calculation_version = deployment.calculation_version
      AND excluded.observed_activated_at = deployment.activated_at
);

-- 기존 statistics에는 어느 deployment에서 파생됐는지 FK가 없다. exact calculation version으로
-- 오염 세대를 제외하고, 정식 seed는 위 eligible forecast view에서 처음부터 다시 계산한다.
CREATE VIEW training_eligible_stop_demand_statistics AS
SELECT statistics.*
FROM stop_demand_statistics statistics
WHERE NOT EXISTS (
    SELECT 1
    FROM training_statistics_generation_exclusion excluded_generation
    WHERE excluded_generation.route_version_id = statistics.route_version_id
      AND excluded_generation.calculation_version = statistics.calculation_version
      AND excluded_generation.revision = statistics.revision
      AND excluded_generation.data_until = statistics.data_until
      AND excluded_generation.computed_at = statistics.computed_at
)
AND NOT EXISTS (
    -- current worker의 임시 모델 경유 기본 집계. final_cutover_at이 아직 없으면 activation 이후를
    -- 전부 fail-closed로 제외한다. freeze가 끝난 뒤에는 cutover 미만 exact window만 제외한다.
    SELECT 1
    FROM training_model_release_exclusion temporary
    WHERE temporary.classification = 'TEMPORARY_RELEASE'
      AND temporary.final_cutover_at IS NULL
      AND statistics.calculation_version IN (
          temporary.calculation_version, 'observed-max-capacity-v1')
      AND statistics.computed_at >= temporary.observed_activated_at
);

CREATE TABLE historical_import_route_binding (
    import_batch_id                 uuid         NOT NULL,
    model_route                     varchar(20)  NOT NULL,
    source_route_id                 varchar(30)  NOT NULL,
    route_reference_version         varchar(80)  NOT NULL,
    archive_roster_sha256           varchar(64)  NOT NULL,
    database_roster_sha256          varchar(64)  NOT NULL,
    route_version_id                bigint       NOT NULL,
    original_valid_from             timestamptz,
    valid_from                      timestamptz  NOT NULL,
    valid_to                        timestamptz,
    mapping_kind                    varchar(32)  NOT NULL,
    CONSTRAINT pk_historical_import_route_binding PRIMARY KEY (import_batch_id, model_route),
    CONSTRAINT fk_historical_binding_batch FOREIGN KEY (import_batch_id)
        REFERENCES historical_import_batch (id),
    CONSTRAINT fk_historical_binding_route_version FOREIGN KEY (route_version_id)
        REFERENCES route_version (id),
    CONSTRAINT ck_historical_binding_archive_digest CHECK (archive_roster_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_historical_binding_database_digest CHECK (database_roster_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_historical_binding_validity CHECK (valid_to IS NULL OR valid_from < valid_to),
    CONSTRAINT ck_historical_binding_kind CHECK (
        mapping_kind IN ('EXTENDED_CURRENT_ROUTE', 'REUSED_EXACT_VERSION')),
    CONSTRAINT ck_historical_binding_original_validity CHECK (
        (mapping_kind = 'EXTENDED_CURRENT_ROUTE') = (original_valid_from IS NOT NULL))
);

CREATE TABLE historical_import_shard (
    import_batch_id                 uuid         NOT NULL,
    shard_ordinal                   integer      NOT NULL,
    kst_date                        date         NOT NULL,
    shard_sha256                    varchar(64)  NOT NULL,
    compressed_bytes                bigint       NOT NULL,
    expected_batch_count            integer      NOT NULL,
    expected_observation_count      bigint       NOT NULL,
    staged_line_count               integer      NOT NULL DEFAULT 0,
    status                          varchar(12)  NOT NULL DEFAULT 'PENDING',
    updated_at                      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_historical_import_shard PRIMARY KEY (import_batch_id, shard_sha256),
    CONSTRAINT ux_historical_import_shard_date_ordinal UNIQUE (import_batch_id, kst_date, shard_ordinal),
    CONSTRAINT fk_historical_import_shard_batch FOREIGN KEY (import_batch_id)
        REFERENCES historical_import_batch (id),
    CONSTRAINT ck_historical_import_shard_digest CHECK (shard_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_historical_import_shard_counts CHECK (
        shard_ordinal >= 0
        AND compressed_bytes >= 0
        AND expected_batch_count >= 0
        AND expected_observation_count >= 0
        AND staged_line_count BETWEEN 0 AND expected_batch_count),
    CONSTRAINT ck_historical_import_shard_status CHECK (status IN ('PENDING', 'STAGING', 'STAGED'))
);

CREATE TABLE historical_import_record (
    import_batch_id                 uuid         NOT NULL,
    source_account                  char(12)     NOT NULL,
    source_record_id                uuid         NOT NULL,
    source_schema_version           varchar(40)  NOT NULL,
    semantic_batch_digest           varchar(64)  NOT NULL,
    normalized_record_sha256        varchar(64)  NOT NULL,
    shard_sha256                    varchar(64)  NOT NULL,
    shard_line_number               integer      NOT NULL,
    model_route                     varchar(20)  NOT NULL,
    source_route_id                 varchar(30)  NOT NULL,
    source_collected_at             timestamptz  NOT NULL,
    kst_date                        date         NOT NULL,
    provider_rows                   integer      NOT NULL,
    stored_rows                     integer      NOT NULL,
    excluded_rows                   integer      NOT NULL,
    status                          varchar(28)  NOT NULL,
    target_observation_batch_id     bigint,
    reject_code                     varchar(60),
    merged_at                       timestamptz,
    CONSTRAINT pk_historical_import_record PRIMARY KEY (import_batch_id, semantic_batch_digest),
    CONSTRAINT ux_historical_import_record_source UNIQUE (import_batch_id, source_account, source_record_id),
    CONSTRAINT ux_historical_import_record_line UNIQUE (import_batch_id, shard_sha256, shard_line_number),
    CONSTRAINT fk_historical_import_record_batch FOREIGN KEY (import_batch_id)
        REFERENCES historical_import_batch (id),
    CONSTRAINT fk_historical_import_record_target FOREIGN KEY (target_observation_batch_id)
        REFERENCES observation_batch (id),
    CONSTRAINT ck_historical_import_record_digests CHECK (
        source_account ~ '^[0-9]{12}$'
        AND semantic_batch_digest ~ '^[0-9a-f]{64}$'
        AND normalized_record_sha256 ~ '^[0-9a-f]{64}$'
        AND shard_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_historical_import_record_counts CHECK (
        provider_rows >= 0
        AND stored_rows >= 0
        AND excluded_rows >= 0
        AND provider_rows = stored_rows + excluded_rows
        AND shard_line_number >= 1),
    CONSTRAINT ck_historical_import_record_status CHECK (
        status IN ('STAGED', 'MERGED', 'DUPLICATE_IMPORT', 'LIVE_OVERLAP', 'REJECTED', 'ROLLED_BACK')),
    CONSTRAINT ck_historical_import_record_target CHECK (
        (status = 'MERGED') = (target_observation_batch_id IS NOT NULL))
);

-- final provenance natural identity. object key/raw hash 대신 source UUID와 vehicle-independent semantic digest를 쓴다.
CREATE TABLE migration_source_record (
    source_account                  char(12)     NOT NULL,
    source_record_id                uuid         NOT NULL,
    semantic_batch_digest           varchar(64)  NOT NULL,
    archive_sha256                  varchar(64)  NOT NULL,
    import_batch_id                 uuid         NOT NULL,
    observation_batch_id            bigint       NOT NULL,
    source_schema_version           varchar(40)  NOT NULL,
    source_collected_at             timestamptz  NOT NULL,
    importer_version                varchar(60)  NOT NULL,
    imported_at                     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_migration_source_record PRIMARY KEY (source_account, source_record_id),
    CONSTRAINT ux_migration_source_semantic UNIQUE (source_account, semantic_batch_digest),
    CONSTRAINT ux_migration_source_batch UNIQUE (observation_batch_id),
    CONSTRAINT fk_migration_source_import FOREIGN KEY (import_batch_id)
        REFERENCES historical_import_batch (id),
    CONSTRAINT fk_migration_source_observation_batch FOREIGN KEY (observation_batch_id)
        REFERENCES observation_batch (id),
    CONSTRAINT ck_migration_source_account CHECK (source_account ~ '^[0-9]{12}$'),
    CONSTRAINT ck_migration_source_semantic CHECK (semantic_batch_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_migration_source_archive CHECK (archive_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE historical_import_stage_batch (
    import_batch_id                 uuid         NOT NULL,
    source_account                  char(12)     NOT NULL,
    source_record_id                uuid         NOT NULL,
    source_schema_version           varchar(40)  NOT NULL,
    semantic_batch_digest           varchar(64)  NOT NULL,
    route_reference_version         varchar(80)  NOT NULL,
    model_route                     varchar(20)  NOT NULL,
    source_route_id                 varchar(30)  NOT NULL,
    scheduled_at                    timestamptz  NOT NULL,
    requested_at                    timestamptz  NOT NULL,
    response_received_at            timestamptz  NOT NULL,
    attempt_key                     varchar(160) NOT NULL,
    http_status                     integer,
    result_code                     integer,
    outcome                         varchar(32)  NOT NULL,
    failure_code                    varchar(32),
    provider_rows                   integer      NOT NULL,
    stored_rows                     integer      NOT NULL,
    excluded_rows                   integer      NOT NULL,
    normalization_version           varchar(40)  NOT NULL,
    collection_strategy_version     varchar(40)  NOT NULL,
    CONSTRAINT pk_historical_import_stage_batch PRIMARY KEY (import_batch_id, semantic_batch_digest),
    CONSTRAINT ux_historical_import_stage_source UNIQUE (import_batch_id, source_account, source_record_id),
    CONSTRAINT fk_historical_stage_batch_record FOREIGN KEY (import_batch_id, semantic_batch_digest)
        REFERENCES historical_import_record (import_batch_id, semantic_batch_digest),
    CONSTRAINT ux_historical_stage_attempt UNIQUE (import_batch_id, model_route, attempt_key),
    CONSTRAINT ck_historical_stage_batch_counts CHECK (
        provider_rows >= 0
        AND stored_rows >= 0
        AND excluded_rows >= 0
        AND provider_rows = stored_rows + excluded_rows),
    CONSTRAINT ck_historical_stage_batch_outcome CHECK (outcome IN (
        'UNKNOWN_AFTER_DISPATCH', 'SUCCESS_ROWS', 'SUCCESS_EMPTY', 'FAILED_UPSTREAM', 'FAILED_UNREADABLE')),
    CONSTRAINT ck_historical_stage_batch_failure CHECK (
        (outcome = 'FAILED_UPSTREAM' AND failure_code IS NOT NULL)
        OR (outcome <> 'FAILED_UPSTREAM' AND failure_code IS NULL))
);

CREATE TABLE historical_import_stage_observation (
    import_batch_id                 uuid         NOT NULL,
    semantic_batch_digest           varchar(64)  NOT NULL,
    source_row_number               integer      NOT NULL,
    vehicle_id                      varchar(40)  NOT NULL,
    stop_order                      integer      NOT NULL,
    stop_id                         varchar(20)  NOT NULL,
    passed_stop_order               integer      NOT NULL,
    running_state                   integer      NOT NULL,
    remaining_seats                 integer,
    seat_unknown_reason             varchar(20),
    crowd_level                     integer,
    vehicle_type                    integer,
    route_type                      integer,
    tagless                         integer,
    CONSTRAINT pk_historical_import_stage_observation PRIMARY KEY (
        import_batch_id, semantic_batch_digest, source_row_number),
    CONSTRAINT fk_historical_stage_observation_batch FOREIGN KEY (import_batch_id, semantic_batch_digest)
        REFERENCES historical_import_stage_batch (import_batch_id, semantic_batch_digest),
    CONSTRAINT ck_historical_stage_row_number CHECK (source_row_number >= 0),
    CONSTRAINT ck_historical_stage_stop_order CHECK (stop_order >= 1 AND passed_stop_order >= 0),
    CONSTRAINT ck_historical_stage_running_state CHECK (running_state IN (0, 1, 2)),
    CONSTRAINT ck_historical_stage_seats CHECK (
        (remaining_seats IS NULL) <> (seat_unknown_reason IS NULL)
        AND remaining_seats >= 0
        AND seat_unknown_reason IN ('REPORTED_UNKNOWN', 'NOT_REPORTED')),
    CONSTRAINT ck_historical_stage_crowd CHECK (crowd_level BETWEEN 1 AND 4)
);

CREATE UNIQUE INDEX ux_historical_stage_vehicle
    ON historical_import_stage_observation (import_batch_id, semantic_batch_digest, vehicle_id);

ALTER TABLE observation_batch
    ADD COLUMN ingestion_origin varchar(20) NOT NULL DEFAULT 'LIVE',
    ADD COLUMN historical_import_batch_id uuid,
    ADD COLUMN semantic_batch_digest varchar(64),
    ADD COLUMN normalized_record_sha256 varchar(64),
    ADD CONSTRAINT fk_observation_batch_historical_import FOREIGN KEY (historical_import_batch_id)
        REFERENCES historical_import_batch (id),
    ADD CONSTRAINT ck_observation_batch_ingestion_origin CHECK (
        ingestion_origin IN ('LIVE', 'S3_BACKFILL')),
    ADD CONSTRAINT ck_observation_batch_origin_provenance CHECK (
        (ingestion_origin = 'LIVE' AND historical_import_batch_id IS NULL)
        OR (ingestion_origin = 'S3_BACKFILL' AND historical_import_batch_id IS NOT NULL)),
    ADD CONSTRAINT ck_observation_batch_historical_provenance CHECK (
        (historical_import_batch_id IS NULL
            AND semantic_batch_digest IS NULL
            AND normalized_record_sha256 IS NULL)
        OR
        (historical_import_batch_id IS NOT NULL
            AND semantic_batch_digest ~ '^[0-9a-f]{64}$'
            AND normalized_record_sha256 ~ '^[0-9a-f]{64}$'));

CREATE INDEX ix_historical_import_record_status
    ON historical_import_record (import_batch_id, status, source_collected_at);

CREATE INDEX ix_historical_stage_batch_merge
    ON historical_import_stage_batch (import_batch_id, response_received_at, semantic_batch_digest);

CREATE INDEX ix_observation_batch_historical_import
    ON observation_batch (historical_import_batch_id, response_received_at)
    WHERE historical_import_batch_id IS NOT NULL;
