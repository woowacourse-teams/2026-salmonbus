-- 당일 성적을 정산 시점에 증분 갱신해 두는 표. 예보는 batch마다 원본을 다시 세지 않고 이 표를 읽는다.
CREATE TABLE same_day_full_outcomes (
    route_id            bigint           NOT NULL,
    outcome_date        date             NOT NULL,
    stops_to_target     integer          NOT NULL,
    row_count           integer          NOT NULL,
    actual_full_count   integer          NOT NULL,
    raw_full_chance_sum double precision NOT NULL,
    settled_through     timestamptz      NOT NULL,
    CONSTRAINT pk_same_day_full_outcomes PRIMARY KEY (route_id, outcome_date, stops_to_target),
    CONSTRAINT fk_same_day_full_outcomes_route FOREIGN KEY (route_id) REFERENCES route (id),
    CONSTRAINT ck_same_day_full_outcomes_counts
        CHECK (row_count >= 0 AND actual_full_count BETWEEN 0 AND row_count)
);
