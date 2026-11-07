-- CSV 기준일과 실제 주변 조회 성공 시각을 분리한다. 기초 명부는 갱신 중에도 계속 읽는다.
SET LOCAL lock_timeout = '1s';
ALTER TABLE bus_station ADD COLUMN baseline_date date;
ALTER TABLE bus_station ADD COLUMN verified_at timestamptz;
ALTER TABLE bus_station ADD COLUMN active boolean NOT NULL DEFAULT true;
UPDATE bus_station SET verified_at = last_seen_at;

CREATE TABLE station_seed_dataset (
    source_id varchar(20) PRIMARY KEY CHECK (source_id = 'GBIS'),
    data_date date NOT NULL,
    file_sha256 char(64) NOT NULL,
    station_count integer NOT NULL CHECK (station_count > 0),
    imported_at timestamptz NOT NULL
);

-- 요청 없는 기존 사전 수집 작업은 실행하지 않는다. 수집 접수는 worker만 쓴다.
ALTER TABLE bus_station_tile ADD COLUMN requested_at timestamptz;
ALTER TABLE bus_station_tile ADD COLUMN refresh_requested_at timestamptz;
-- 이번 회차의 연속 실패 수. 상한에 닿으면 접수를 거두어 대기열 상한을 계속 차지하지 않게 한다.
ALTER TABLE bus_station_tile ADD COLUMN failure_count integer NOT NULL DEFAULT 0;
CREATE INDEX ix_station_requested_refresh ON bus_station_tile(priority, refresh_requested_at)
    WHERE refresh_requested_at IS NOT NULL;
