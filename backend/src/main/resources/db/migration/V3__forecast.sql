-- 예보가 읽고 쓰는 두 표.
-- 발행 계층(forecast_publication · stop_prediction)은 v4 계약에서 빠졌고,
-- 이 DB 에는 발행본이 쌓인 적이 없어 만들지 않는다.
--
-- CHECK 은 값의 정의가 정한 것만 남긴다. 모델이 바뀌어도 안 바뀌는 값들이다.
-- 확률은 0 에서 1 사이이고, 앞으로 지날 정류소 수는 api 문서가 12 로 막았다.
--
-- PostgreSQL 은 NaN 을 정렬하고 인덱스에 넣을 수 있게 하려고 NaN = NaN 을 참으로 본다.
-- 그래서 x = x 로는 NaN 이 안 걸러진다(postgres 18 실측). 대신 NaN 을 모든 수보다 크게
-- 다루므로 BETWEEN 이 NaN 과 Infinity 를 둘 다 막는다.
-- 상한이 없는 expected_seats 만 <> 'NaN' 을 따로 건다.

CREATE TABLE stop_demand_statistics (
    route_version_id           bigint           NOT NULL,
    stop_order                 integer          NOT NULL,
    time_slot                  varchar(40)      NOT NULL, -- morning | evening | other
    calculation_version        varchar(40)      NOT NULL, -- 정원 상수가 바뀌면 값의 뜻이 달라져 키에 넣는다
    revision                   integer          NOT NULL, -- 몇 번째 배치 산출물인가. 덮어쓸 때마다 오른다
    average_fill_rate          double precision NOT NULL, -- 도착 시 자리가 찬 비율의 평균
    average_net_boarding_rate  double precision NOT NULL, -- (탄 사람 - 내린 사람) / 정원. 음수가 정상
    sample_count               integer          NOT NULL,
    data_until                 timestamptz      NOT NULL,
    computed_at                timestamptz      NOT NULL,
    CONSTRAINT pk_stop_demand_statistics PRIMARY KEY (route_version_id, stop_order, time_slot, calculation_version),
    CONSTRAINT fk_statistics_route_stop FOREIGN KEY (route_version_id, stop_order)
        REFERENCES route_stop (route_version_id, stop_order),
    CONSTRAINT ck_statistics_fill_rate CHECK (average_fill_rate BETWEEN 0 AND 1),
    CONSTRAINT ck_statistics_net_boarding_rate CHECK (average_net_boarding_rate BETWEEN -1 AND 1)
);

CREATE TABLE seat_forecast (
    vehicle_observation_id      bigint           NOT NULL,
    target_stop_order           integer          NOT NULL,
    route_version_id            bigint           NOT NULL,
    stops_to_target             integer          NOT NULL, -- target_stop_order - 관측 stop_order
    model_deployment_id         bigint           NOT NULL,
    demand_statistics_revision  integer          NOT NULL, -- 통계는 덮어써진다. 몇 번째 판을 읽었는지 남긴다
    seat_full_chance_raw        double precision NOT NULL, -- 만석일 확률. 사전확률 이동 전
    seat_full_chance            double precision NOT NULL, -- 응답에는 1 - 이 값이 나간다
    expected_seats              double precision,
    generated_at                timestamptz      NOT NULL,
    scoring_state               varchar(16)      NOT NULL, -- PENDING | SETTLED | SKIPPED | LOST | SEAT_MISSING
    arrival_observation_id      bigint,                    -- 그 정류소에 실제로 도착했을 때의 관측
    seats_on_arrival            integer,                   -- 도착했을 때 남은 자리. 0 이면 만석
    scored_at                   timestamptz,
    CONSTRAINT pk_seat_forecast PRIMARY KEY (vehicle_observation_id, target_stop_order),
    CONSTRAINT fk_forecast_observation FOREIGN KEY (vehicle_observation_id, route_version_id)
        REFERENCES vehicle_observation (id, route_version_id),
    CONSTRAINT fk_forecast_target_stop FOREIGN KEY (route_version_id, target_stop_order)
        REFERENCES route_stop (route_version_id, stop_order),
    CONSTRAINT fk_forecast_deployment FOREIGN KEY (model_deployment_id)
        REFERENCES model_deployment (id),
    -- 아직 못 정한 것. 개발 요청서 BE N17 :
    --   위 fk_forecast_observation 은 판본을 같이 보는데 이것은 (id) 하나뿐이라,
    --   도착 관측이 다른 판본의 행이어도 DB 가 막지 않는다. 복합으로 바꾸면 회수 배치가
    --   판본을 같이 넘겨야 한다. 여정 키에 판본이 들어 있어 실무상 잘 안 나므로 급하지 않다.
    CONSTRAINT fk_forecast_arrival FOREIGN KEY (arrival_observation_id)
        REFERENCES vehicle_observation (id),
    CONSTRAINT ck_forecast_chance_raw CHECK (seat_full_chance_raw BETWEEN 0 AND 1),
    CONSTRAINT ck_forecast_chance CHECK (seat_full_chance BETWEEN 0 AND 1),
    CONSTRAINT ck_forecast_expected_seats CHECK (
        expected_seats IS NULL
        OR (expected_seats >= 0 AND expected_seats <> 'NaN'::double precision)
    ),
    CONSTRAINT ck_forecast_stops_to_target CHECK (stops_to_target BETWEEN 1 AND 12)
);
