SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DROP TRIGGER new_route_statistics ON route_version;
DROP FUNCTION initialize_new_route_statistics();
DROP TABLE stop_demand_pending_sample, stop_demand_current_total,
    stop_demand_baseline, stop_demand_vehicle, stop_demand_run,
    stop_demand_capacity_stage, stop_demand_day_stage, stop_demand_cell_stage,
    same_day_model_full_outcomes, same_day_full_outcomes;

-- 과거 예보와 파일을 해석하는 데 필요한 확정 통계/판본은 그대로 보존한다.
-- stop_demand_statistics, demand_statistics_version, file_statistics_publication은 읽기 자료다.
