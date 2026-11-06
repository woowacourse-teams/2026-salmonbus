-- 검수된 표시 형상을 원본 입력 지문에 귀속한다. 자동 대체는 현재 원본에서 결정적으로 만든다.
CREATE TABLE route_display_geometry (
    id BIGSERIAL PRIMARY KEY,
    route_id BIGINT NOT NULL REFERENCES route(id) ON DELETE CASCADE,
    input_digest VARCHAR(64) NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    content_digest VARCHAR(64) NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    document JSONB NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    disabled_at TIMESTAMPTZ,
    UNIQUE(route_id, input_digest, content_digest)
);
CREATE INDEX idx_route_display_geometry_current
    ON route_display_geometry(route_id, input_digest, registered_at DESC, id DESC)
    WHERE disabled_at IS NULL;

-- 원본 points를 바꾸지 않고 보조 조회의 성공·실패 및 점 분류 근거를 보존한다.
ALTER TABLE route_geometry ADD COLUMN evidence JSONB NOT NULL
    DEFAULT '{"selectedSource":"GBIS_API","sources":[],"webPoints":[]}'::jsonb;
ALTER TABLE route_geometry ADD COLUMN evidence_observed_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity';
CREATE TABLE route_geometry_source_cache (
    source_id VARCHAR(64) PRIMARY KEY,
    fetched_at TIMESTAMPTZ NOT NULL,
    content_digest VARCHAR(64) NOT NULL CHECK(content_digest ~ '^[0-9a-f]{64}$'),
    document JSONB NOT NULL
);
