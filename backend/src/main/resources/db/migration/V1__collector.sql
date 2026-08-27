-- 수집기가 쓰는 여섯 표.
-- 제약은 키(PK · UNIQUE · FK)와 배타 범위까지만 건다.
-- 값의 범위 규칙(CHECK)은 그 규칙을 실제로 쓰는 티켓에서 조인다. SAL-82 · SAL-85 · SAL-88.
--
-- 아직 못 정한 것 셋
--   1. observation_batch 라는 이름이 확정이 아니다. fleet_snapshot · collection_attempt 가 후보다.
--   2. route.public_route_id 를 둘지 못 정했다. route 표 위 주석 참고.
--   3. vehicle_observation 이 route_stop 을 보는 복합 FK 가 관측을 버린다. 그 FK 위 주석 참고.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE daily_call_quota (
    provider        varchar(30) NOT NULL, -- GBIS · 경기데이터드림
    api_service     varchar(60) NOT NULL, -- 한도는 서비스키 단위로 걸린다
    kst_date        date        NOT NULL, -- 한도는 한국 자정에 리셋된다
    reserved_calls  integer     NOT NULL DEFAULT 0,
    daily_limit     integer     NOT NULL, -- 운영계정 기준 10,000
    CONSTRAINT pk_daily_call_quota PRIMARY KEY (provider, api_service, kst_date)
);

-- public_route_id 를 두고 팀 문서 둘이 어긋난다. 지금은 source_route_id 와 같은 값이 들어간다.
--   결정 기록 08-17 : 합성 ID(gg-3330)를 안 만들고 상류 원문을 공개 ID 로 쓴다
--   개발 요청서     : publicRouteId 는 gg-3330 이고 도입 여부가 미결이다.
--                     지금 응답의 route.id 로 나가는 것은 source_route_id 다
-- 공개 ID 가 확정되면 둘 중 하나를 지운다.
CREATE TABLE route (
    id               bigint      GENERATED ALWAYS AS IDENTITY,
    public_route_id  varchar(30) NOT NULL, -- Open API 원문 routeId. 9자리
    source_id        varchar(40) NOT NULL,
    source_route_id  varchar(30) NOT NULL, -- 상류 호출 · API 경로 파라미터 · 응답 route.id
    display_name     varchar(40) NOT NULL, -- 표시명 3330. 모델 노선 키와 글자가 같아도 다른 개념이다
    start_stop_name  varchar(60) NOT NULL,
    end_stop_name    varchar(60) NOT NULL,
    CONSTRAINT pk_route PRIMARY KEY (id),
    CONSTRAINT ux_route_public_route_id UNIQUE (public_route_id)
);

-- 응답의 route.referenceVersionId("3330-v5")는 이 표의 id 를 그대로 내보낸다.
-- 별도 열을 두지 않는 이유.
--   판본이 새로 생기면 새 행이라 id 가 바뀌고, 그것이 곧 클라이언트의
--   "개편 중이니 화면을 다시 받아라" 신호다. 계약은 String 이라는 것 외에 형식을 정하지 않았고,
--   상류 GBIS 도 판본 개념을 주지 않는다(노선정보 응답 43개 항목 어디에도 없다).
--   시간표(첫차·막차 4열)만 바뀌면 판본을 새로 안 끊고 같은 행을 UPDATE 하므로 id 도 안 움직인다.
CREATE TABLE route_version (
    id                         bigint      GENERATED ALWAYS AS IDENTITY,
    route_id                   bigint      NOT NULL,
    turn_sequence              integer,              -- 회차 지점의 순번
    up_first_departure_time    varchar(5),           -- KST HH:MM
    up_last_departure_time     varchar(5),
    down_first_departure_time  varchar(5),
    down_last_departure_time   varchar(5),           -- 1650 은 상행 22:35 · 하행 23:55
    -- 상류가 개편을 알려주지 않아 정류소 목록을 해시해 바뀐 것을 알아낸다.
    -- UNIQUE 를 걸지 않는다. 공사로 A 에서 B 로 갔다가 다시 A 로 돌아오면 해시가 되풀이되는데,
    -- 그때도 새 판본을 끊어야 한다. 기간이 다르고, 관측이 판본에 매달리고,
    -- 무엇보다 id 가 그대로면 클라이언트가 개편을 못 알아챈다.
    -- "직전과 같으면 새로 안 만든다"는 가장 최근 판본과만 비교하는 규칙이라 적재 로직에서 본다.
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
    stop_id           varchar(20) NOT NULL, -- stationId. UNIQUE 를 안 건다. 같은 정류소를 두 번 지나면 적재가 막힌다
    name              varchar(60) NOT NULL,
    direction         varchar(4)  NOT NULL, -- UP · DOWN
    boarding_allowed  boolean     NOT NULL, -- NOT stop_id LIKE '277%'. 1650 24곳 · 3330 7곳이 불가
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
    response_received_at   timestamptz,           -- 응답 observedAt 의 권위. queryTime 이 아니다
    forecast_completed_at  timestamptz,           -- 차가 0대라 예보 행이 없어도 찍는다
    completed_at           timestamptz,
    http_status            integer,
    result_code            integer,               -- Open API resultCode 원문
    outcome                varchar(32)  NOT NULL,
    failure_code           varchar(32),
    provider_rows          integer,               -- 실측 17
    stored_rows            integer,               -- 응답 vehiclesInService
    excluded_rows          integer,
    CONSTRAINT pk_observation_batch PRIMARY KEY (id),
    CONSTRAINT fk_batch_route_version FOREIGN KEY (route_version_id)
        REFERENCES route_version (id),
    CONSTRAINT ux_batch_attempt UNIQUE (route_version_id, attempt_key),
    CONSTRAINT ux_batch_context UNIQUE (id, route_version_id)
);

-- 확정된 /board 조회가 요청마다 도는 질의다.
-- "그 판본의, 예보까지 끝난, 가장 최근 묶음" 하나를 고른다.
-- 이 표는 수집 한 번에 한 행씩 늘어 하루 8,556 행, 한 해 310만 행이 된다.
-- 부분 조건은 조회의 WHERE 와 글자 그대로 같아야 계획기가 이 인덱스를 고른다.
--
-- 다른 조회 경로에는 인덱스를 붙이지 않았다. 읽는 코드가 아직 없어 어떤 질의가 될지 모른다.
-- 직전 도착 차량(SAL-95), 여정 잇기(SAL-94), 채점 회수(SAL-97) 를 구현할 때
-- 그쪽에서 자기 질의를 보고 붙이거나 고치면 된다. 그 시점에 한 번 모여 같이 보면 좋겠다.
CREATE INDEX ix_batch_forecast_ready
    ON observation_batch (route_version_id, response_received_at DESC)
    WHERE outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY') AND forecast_completed_at IS NOT NULL;

CREATE TABLE vehicle_observation (
    id                    bigint       GENERATED ALWAYS AS IDENTITY,
    observation_batch_id  bigint       NOT NULL,
    route_version_id      bigint       NOT NULL, -- 순번의 뜻을 정하는 판본
    source_row_number     integer      NOT NULL,
    observed_at           timestamptz  NOT NULL, -- queryTime. 같은 묶음은 밀리초까지 같다
    vehicle_id            varchar(40),           -- vehId. 해시하지 않는다. 차량별 정원 조회에 원문이 필요하다
    vehicle_trip_key      varchar(120),          -- 회차해 돌아온 차와 아직 안 온 차를 가른다
    plate_number          varchar(20),           -- plateNo
    stop_order            integer      NOT NULL, -- 통과 순번. stationSeq 원문이 아니다
    stop_id               varchar(20)  NOT NULL, -- stationId
    running_state         integer,               -- stateCd. 실측 0 · 1 · 2
    remaining_seats       integer,               -- remainSeatCnt. 실측에 -1(미제공) 섞임
    crowd_level           integer,               -- crowded. 실측 0 · 1. 0 이 나온다
    vehicle_type          integer,               -- lowPlate. 정원이 여기서 유도된다(2층 68 · 저상 40 · 그 밖 45)
    route_type            integer,               -- routeTypeCd. 실측 전건 11
    tagless               integer,               -- taglessCd. 실측 0 · 1
    CONSTRAINT pk_vehicle_observation PRIMARY KEY (id),
    CONSTRAINT fk_observation_batch FOREIGN KEY (observation_batch_id, route_version_id)
        REFERENCES observation_batch (id, route_version_id),
    -- 이 FK 가 관측을 버린다. 드문 일이 아니다. 개발 요청서 B19 :
    --   통과 순번은 stateCd=1(도착)이면 stationSeq-1 이라, stationSeq=1 이고 stateCd=1 이면 0 이 나온다.
    --   route_stop.stop_order 는 1 부터라 그 행은 적재가 실패한다. 첫 정류소에 도착하는 버스마다 걸린다.
    --   노선 개편으로 순번이 밀린 동안에도 같은 일이 난다.
    -- FK 를 빼는 것으로는 안 풀린다. seat_forecast 의 FK 는 (판본, 순번)만 보고 stop_id 는 안 봐서,
    --   빼면 어긋난 관측이 저장되고 틀린 정류소에 예보가 붙는다.
    -- SAL-82(정규화) · SAL-84(관측 저장)가 같이 정한다. SAL-83 범위 밖이다.
    CONSTRAINT fk_observation_route_stop FOREIGN KEY (route_version_id, stop_order, stop_id)
        REFERENCES route_stop (route_version_id, stop_order, stop_id),
    CONSTRAINT ux_observation_source_row UNIQUE (observation_batch_id, source_row_number),
    CONSTRAINT ux_observation_context UNIQUE (id, route_version_id)
);

-- 재시도가 같은 차량을 한 묶음에 두 번 쌓는 것을 막는다
CREATE UNIQUE INDEX ux_observation_vehicle_per_batch
    ON vehicle_observation (observation_batch_id, vehicle_id)
    WHERE vehicle_id IS NOT NULL;
