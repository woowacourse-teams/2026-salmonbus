-- 제약은 키(PK · UNIQUE · FK)와 배타 범위까지만 건다.
-- 값의 범위 규칙(CHECK)은 그 규칙을 실제로 쓰는 티켓에서 조인다. SAL-82 · SAL-85 · SAL-88.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE daily_call_quota (
    provider        varchar(30) NOT NULL, -- GBIS · 경기데이터드림
    api_service     varchar(60) NOT NULL, -- 한도는 활용신청한 API 마다 따로 걸린다
    kst_date        date        NOT NULL, -- 한도는 한국 자정에 리셋된다
    reserved_calls  integer     NOT NULL DEFAULT 0,
    daily_limit     integer     NOT NULL, -- 운영계정 기준 10,000
    CONSTRAINT pk_daily_call_quota PRIMARY KEY (provider, api_service, kst_date)
);

-- public_route_id 는 결정 기록(08-17)과 개발 요청서가 어긋나 있다. 지금은 source_route_id 와 같은 값이다.
-- 공개 ID 가 확정되면 둘 중 하나를 지운다.
CREATE TABLE route (
    id               bigint      GENERATED ALWAYS AS IDENTITY,
    public_route_id  varchar(30) NOT NULL, -- Open API 원문 routeId. 9자리
    source_id        varchar(40) NOT NULL,
    source_route_id  varchar(30) NOT NULL,
    display_name     varchar(40) NOT NULL, -- 표시명 3330. 모델 노선 키와 글자가 같아도 다른 개념이다
    start_stop_name  varchar(60) NOT NULL,
    end_stop_name    varchar(60) NOT NULL,
    CONSTRAINT pk_route PRIMARY KEY (id),
    CONSTRAINT ux_route_public_route_id UNIQUE (public_route_id)
);

-- 응답의 route.referenceVersionId 는 이 표의 id 를 그대로 내보낸다.
-- 판본이 새로 생기면 id 가 바뀌고, 그것이 클라이언트에게 "화면을 다시 받아라" 신호다.
CREATE TABLE route_version (
    id                         bigint      GENERATED ALWAYS AS IDENTITY,
    route_id                   bigint      NOT NULL,
    turn_sequence              integer,              -- 회차 지점의 순번
    up_first_departure_time    varchar(5),           -- KST HH:MM
    up_last_departure_time     varchar(5),
    down_first_departure_time  varchar(5),
    down_last_departure_time   varchar(5),
    -- 상류가 개편을 안 알려줘서 정류소 목록을 해시해 감지한다.
    -- UNIQUE 를 안 건다. 공사로 A 에서 B 로 갔다가 A 로 돌아오면 해시가 되풀이되는데 그때도 새 판본이다.
    content_digest             char(64)    NOT NULL,
    valid_from                 timestamptz NOT NULL,
    valid_to                   timestamptz,          -- NULL = 지금 쓰는 판본
    CONSTRAINT pk_route_version PRIMARY KEY (id),
    CONSTRAINT fk_route_version_route FOREIGN KEY (route_id)
        REFERENCES route (id),
    CONSTRAINT ex_route_version_no_overlap EXCLUDE USING gist (
        route_id WITH =,
        tstzrange(valid_from, valid_to) WITH &&
    )
);

CREATE TABLE route_stop (
    route_version_id  bigint      NOT NULL,
    stop_order        integer     NOT NULL, -- stationSeq. 회차로 같은 정류소가 두 번 나와 이것이 정체성이다
    stop_id           varchar(20) NOT NULL, -- stationId. 같은 정류소를 두 번 지나므로 UNIQUE 를 안 건다
    name              varchar(60) NOT NULL,
    direction         varchar(4)  NOT NULL, -- UP · DOWN
    boarding_allowed  boolean     NOT NULL, -- NOT stop_id LIKE '277%'
    CONSTRAINT pk_route_stop PRIMARY KEY (route_version_id, stop_order),
    CONSTRAINT fk_route_stop_version FOREIGN KEY (route_version_id)
        REFERENCES route_version (id),
    CONSTRAINT ux_route_stop_context UNIQUE (route_version_id, stop_order, stop_id)
);

CREATE TABLE observation_batch (
    id                     bigint       GENERATED ALWAYS AS IDENTITY,
    route_version_id       bigint       NOT NULL,
    scheduled_at           timestamptz  NOT NULL,
    attempt_number         integer      NOT NULL,
    attempt_key            varchar(160) NOT NULL, -- 재시도해도 값이 같아 묶음이 두 개 안 생긴다
    requested_at           timestamptz  NOT NULL,
    response_received_at   timestamptz,           -- 관측 시각의 권위. queryTime 이 아니다
    forecast_completed_at  timestamptz,           -- 차가 0대라 예보 행이 없어도 찍는다
    completed_at           timestamptz,
    http_status            integer,
    result_code            integer,               -- Open API resultCode 원문
    outcome                varchar(32)  NOT NULL,
    failure_code           varchar(32),
    provider_rows          integer,
    stored_rows            integer,               -- 응답 vehiclesInService
    excluded_rows          integer,
    CONSTRAINT pk_observation_batch PRIMARY KEY (id),
    CONSTRAINT fk_batch_route_version FOREIGN KEY (route_version_id)
        REFERENCES route_version (id),
    CONSTRAINT ux_batch_attempt UNIQUE (route_version_id, attempt_key),
    CONSTRAINT ux_batch_context UNIQUE (id, route_version_id)
);

-- /board 가 요청마다 도는 질의. 부분 조건이 조회의 WHERE 와 글자 그대로 같아야 계획기가 이걸 고른다.
CREATE INDEX ix_batch_forecast_ready
    ON observation_batch (route_version_id, response_received_at DESC)
    WHERE outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY') AND forecast_completed_at IS NOT NULL;

CREATE TABLE vehicle_observation (
    id                    bigint       GENERATED ALWAYS AS IDENTITY,
    observation_batch_id  bigint       NOT NULL,
    route_version_id      bigint       NOT NULL, -- 순번의 뜻을 정하는 판본
    source_row_number     integer      NOT NULL,
    observed_at           timestamptz  NOT NULL, -- observation_batch.response_received_at 과 같은 값
    vehicle_id            varchar(40),           -- vehId. 차량별 정원 조회에 원문이 필요해 해시하지 않는다
    vehicle_trip_key      varchar(120),          -- 회차해 돌아온 차와 아직 안 온 차를 가른다
    plate_number          varchar(20),           -- plateNo
    stop_order            integer      NOT NULL, -- stationSeq 원문. 통과 순번은 running_state 로 계산한다
    stop_id               varchar(20)  NOT NULL, -- stationId. stop_order 와 같은 정류소여야 한다
    running_state         integer,               -- stateCd. 실측 0 · 1 · 2
    remaining_seats       integer,               -- remainSeatCnt
    crowd_level           integer,               -- crowded. 0 은 미제공이라 저장 전에 접는다
    vehicle_type          integer,               -- lowPlate. 정원이 여기서 유도된다(2층 68 · 저상 40 · 그 밖 45)
    route_type            integer,               -- routeTypeCd
    tagless               integer,               -- taglessCd
    CONSTRAINT pk_vehicle_observation PRIMARY KEY (id),
    CONSTRAINT fk_observation_batch FOREIGN KEY (observation_batch_id, route_version_id)
        REFERENCES observation_batch (id, route_version_id),
    -- 노선 개편으로 상류 순번과 우리 정류소 목록이 어긋나는 동안 관측이 버려진다. SAL-84 · SAL-88 몫.
    CONSTRAINT fk_observation_route_stop FOREIGN KEY (route_version_id, stop_order, stop_id)
        REFERENCES route_stop (route_version_id, stop_order, stop_id),
    CONSTRAINT ux_observation_source_row UNIQUE (observation_batch_id, source_row_number),
    CONSTRAINT ux_observation_context UNIQUE (id, route_version_id)
);

-- 재시도가 같은 차량을 한 묶음에 두 번 쌓는 것을 막는다
CREATE UNIQUE INDEX ux_observation_vehicle_per_batch
    ON vehicle_observation (observation_batch_id, vehicle_id)
    WHERE vehicle_id IS NOT NULL;
