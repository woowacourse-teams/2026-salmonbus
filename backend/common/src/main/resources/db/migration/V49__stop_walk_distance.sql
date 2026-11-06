-- 정류장 쌍 도보 거리. 오프라인 일괄 계산 자료를 통째로 적재한다. 노선·판본과 무관하게 정류장 ID 쌍으로 묶는다.
-- 계산에 쓴 두 좌표를 같이 둬서, 현재 판본 좌표가 달라진 쌍을 조회 때 가려낼 수 있다.
-- stop_walk_dataset은 지금 stop_walk_distance에 들어 있는 묶음 한 행만 둔다. 이력은 남기지 않는다.
SET LOCAL lock_timeout = '1s';
CREATE TABLE stop_walk_dataset (
    checksum char(64) PRIMARY KEY CHECK (checksum ~ '^[0-9a-f]{64}$'),
    osm_base timestamptz NOT NULL,
    pair_count integer NOT NULL CHECK (pair_count >= 0),
    loaded_at timestamptz NOT NULL
);
CREATE TABLE stop_walk_distance (
    station_a varchar(20) NOT NULL,
    station_b varchar(20) NOT NULL,
    a_x double precision NOT NULL,
    a_y double precision NOT NULL,
    b_x double precision NOT NULL,
    b_y double precision NOT NULL,
    walk_meters integer NOT NULL CHECK (walk_meters >= 0),
    CONSTRAINT pk_stop_walk_distance PRIMARY KEY (station_a, station_b),
    CONSTRAINT ck_stop_walk_distance_order CHECK (station_a < station_b)
);
CREATE INDEX ix_stop_walk_distance_station_b ON stop_walk_distance (station_b, station_a);
CREATE UNIQUE INDEX ux_stop_walk_dataset_single_row ON stop_walk_dataset ((true));
