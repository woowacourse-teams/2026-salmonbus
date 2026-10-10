-- 파일 계산의 결과와 입력 식별 기록은 같은 트랜잭션으로 저장한다.
-- 원자료 이관 또는 삭제 완료를 나타내는 장부가 아니다.
CREATE TABLE file_statistics_publication (
    route_version_id bigint NOT NULL,
    input_sha256 varchar(64) NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    calculation_version varchar(40) NOT NULL,
    revision integer NOT NULL,
    PRIMARY KEY (route_version_id, input_sha256),
    FOREIGN KEY (route_version_id, calculation_version, revision)
        REFERENCES demand_statistics_version(route_version_id, calculation_version, revision)
);
