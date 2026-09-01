-- 셀 통계의 평균이 날짜 균등가중이라 날짜 수를 같이 둔다. 집계 배치를 배선하는 SAL-95 가 조인다.
--
-- sample_count 만으로는 평균을 다시 합칠 수 없다. 수집이 적응형이라 날짜별 관측 수가
-- 726 에서 6,206 까지 벌어지는데 그 수로 가중하면 수요가 아니라 수집 밀도가 통계에 샌다.
-- 그래서 날짜마다 평균을 내고 날짜끼리 단순 평균한다. 그때 나눈 날짜 수가 이 열이다.
--
-- V1 ~ V8 은 고치지 않는다. Flyway 의 checksum 은 주석까지 포함해 계산해서, 한 글자만 바꿔도
-- 그 파일을 이미 돌린 DB 가 기동을 거부한다.
--
-- 이 표에 행이 하나도 없다는 전제로 NOT NULL 을 기본값 없이 붙인다. 집계가 여태 돈 적이 없다.

ALTER TABLE stop_demand_statistics
    ADD COLUMN day_count integer NOT NULL;

ALTER TABLE stop_demand_statistics
    ADD CONSTRAINT ck_statistics_sample_count_not_negative
        CHECK (sample_count >= 0),
    ADD CONSTRAINT ck_statistics_day_count_not_negative
        CHECK (day_count >= 0),
    -- 한 날짜에 표본이 최소 하나다. 날짜 수가 표본 수보다 크면 둘 중 하나가 틀린 것이다
    ADD CONSTRAINT ck_statistics_day_count_within_sample_count
        CHECK (day_count <= sample_count),
    -- 집계가 한 판 돌 때마다 오른다. 한 번도 안 돈 세대는 행이 없지 0 으로 남지 않는다
    ADD CONSTRAINT ck_statistics_revision_positive
        CHECK (revision >= 1);
