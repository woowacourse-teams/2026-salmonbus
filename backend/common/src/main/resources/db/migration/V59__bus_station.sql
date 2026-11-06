-- 정류장 지도의 정류장 원본. GBIS 주변정류소 조회로 줌 14 칸 단위로 미리 받아 둔다.
-- 우리 노선 정류장의 원본은 계속 route_stop이다. 관측·예보·카탈로그는 이 표를 읽지 않으며 외래 키도 두지 않는다.
SET LOCAL lock_timeout = '1s';

CREATE TABLE bus_station (
    station_id    varchar(20)      PRIMARY KEY,
    name          varchar(80)      NOT NULL,
    x             double precision NOT NULL,
    y             double precision NOT NULL,
    mobile_no     varchar(10),
    region_name   varchar(20),
    center_lane   boolean,
    tile_x        integer          NOT NULL,
    tile_y        integer          NOT NULL,
    first_seen_at timestamptz      NOT NULL,
    last_seen_at  timestamptz      NOT NULL,
    CHECK (station_id ~ '^[0-9]{9}$' AND station_id NOT LIKE '277%')
);
CREATE INDEX ix_bus_station_tile ON bus_station(tile_x, tile_y);

-- 채울 칸 목록이자 진행 기록. 한 번 다 채운 칸은 다시 채우는 동안에도 다 채운 칸으로 응답한다.
CREATE TABLE bus_station_tile (
    tile_x                    integer     NOT NULL,
    tile_y                    integer     NOT NULL,
    priority                  smallint    NOT NULL CHECK (priority IN (0, 1, 2)),
    queued_at                 timestamptz NOT NULL,
    pass_started_at           timestamptz,
    probes_done               integer     NOT NULL DEFAULT 0 CHECK (probes_done BETWEEN 0 AND 12),
    completed_pass_started_at timestamptz,
    completed_at              timestamptz,
    station_count             integer,
    failure_reason            varchar(40),
    retry_after_at            timestamptz,
    PRIMARY KEY (tile_x, tile_y)
);
CREATE INDEX ix_bus_station_tile_next ON bus_station_tile(priority, queued_at) WHERE completed_at IS NULL;
