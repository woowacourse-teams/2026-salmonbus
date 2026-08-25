-- 예보가 읽고 쓰는 두 표.
-- 발행 계층(forecast_publication · stop_prediction)은 v4 계약에서 빠졌고,
-- 이 DB 에는 발행본이 쌓인 적이 없어 만들지 않는다.
--
-- CHECK 은 둘만 남긴다.
--   NaN 가드 — double precision 은 NaN 을 담을 수 있고 응답이 1 - NaN 이 되면 안 된다.
--              범위 규칙과 달리 모델이 바뀌어도 안 바뀌는 값이다. x = x 가 거짓인 값은 NaN 하나뿐이다.
--   지평 상한 — 계약이 정한 범위라 모델과 무관하다.

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
    CONSTRAINT ck_statistics_not_nan CHECK (
        average_fill_rate = average_fill_rate
        AND average_net_boarding_rate = average_net_boarding_rate
    )
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
    -- 아직 못 정한 것 — 개발 요청서 BE N17 :
    --   위 fk_forecast_observation 은 판본을 같이 보는데 이것은 (id) 하나뿐이라,
    --   도착 관측이 다른 판본의 행이어도 DB 가 막지 않는다. 복합으로 바꾸면 회수 배치가
    --   판본을 같이 넘겨야 한다. 여정 키에 판본이 들어 있어 실무상 잘 안 나므로 급하지 않다.
    CONSTRAINT fk_forecast_arrival FOREIGN KEY (arrival_observation_id)
        REFERENCES vehicle_observation (id),
    CONSTRAINT ck_forecast_not_nan CHECK (
        seat_full_chance_raw = seat_full_chance_raw
        AND seat_full_chance = seat_full_chance
        AND (expected_seats IS NULL OR expected_seats = expected_seats)
    ),
    -- 계약이 정한 범위다. 모델이 바뀌어도 안 바뀐다
    CONSTRAINT ck_forecast_stops_to_target CHECK (stops_to_target BETWEEN 1 AND 12)
);

-- 아직 안 닫힌 예보 — 채점 배치가 집는 경로
CREATE INDEX ix_forecast_pending
    ON seat_forecast (route_version_id, generated_at)
    WHERE scoring_state = 'PENDING';
