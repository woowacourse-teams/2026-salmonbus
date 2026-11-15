SET LOCAL lock_timeout = '5s';

-- 좌석 예보 발행·정산·줄 단위 이관이 쓰던 테이블·뷰·함수·트리거를 지운다. 지금 코드는 이 객체들을 읽거나 쓰지 않는다.
--
-- 아래 테이블 가운데 하나라도 줄이 있으면 아무것도 지우지 않고 끝낸다. 줄이 있는 DB는 테이블을 파일로 내보내 확인한 뒤
-- backend/deploy/sql의 삭제 SQL로 지운다. 그렇게 지운 DB와 이 마이그레이션이 지운 DB는 같은 스키마가 된다.
-- alighting_settlement_queue_scan은 만들 때 넣은 한 줄뿐이라 줄 확인에서 뺀다.
DO $$
DECLARE
    retired_tables CONSTANT text[] := ARRAY[
        'alighting_settlement_pass', 'alighting_settlement_observation', 'alighting_settlement_projection',
        'alighting_settlement_batch_work', 'alighting_settlement_generation', 'alighting_settlement_queue_scan',
        'alighting_forecast_evaluation_result', 'alighting_evaluation_archive', 'alighting_forecast_evaluation_work',
        'forecast_evaluation_result', 'forecast_evaluation_compact', 'forecast_evaluation_work',
        'archive_legacy_member', 'archive_legacy_batch', 'alighting_history_checkpoint',
        'seat_forecast', 'forecast_publication',
        'stop_demand_statistics', 'file_statistics_publication', 'demand_statistics_version',
        'model_training_exclusion', 'statistics_training_exclusion',
        'operational_archive_file', 'operational_archive_job', 'operational_archive_boundary'
    ];
    table_name text;
    has_rows boolean;
BEGIN
    FOREACH table_name IN ARRAY retired_tables LOOP
        IF to_regclass(quote_ident(table_name)) IS NULL OR table_name = 'alighting_settlement_queue_scan' THEN
            CONTINUE;
        END IF;
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I)', table_name) INTO has_rows;
        IF has_rows THEN
            RAISE NOTICE '% 테이블에 줄이 있어 아무것도 지우지 않는다', table_name;
            RETURN;
        END IF;
    END LOOP;

    DROP TRIGGER IF EXISTS trg_alighting_evaluation_work ON seat_forecast;
    DROP TRIGGER IF EXISTS trg_alighting_settlement_batch ON alighting_forecast_evaluation_work;
    DROP TRIGGER IF EXISTS trg_alighting_settlement_batch_cleanup ON alighting_forecast_evaluation_work;
    DROP TRIGGER IF EXISTS trg_alighting_batch_insert ON observation_batch;
    DROP TRIGGER IF EXISTS trg_alighting_batch_update ON observation_batch;
    DROP TRIGGER IF EXISTS trg_alighting_batch_delete ON observation_batch;
    DROP TRIGGER IF EXISTS trg_alighting_observation_insert ON vehicle_observation;
    DROP TRIGGER IF EXISTS trg_alighting_observation_update ON vehicle_observation;
    DROP TRIGGER IF EXISTS trg_alighting_observation_delete ON vehicle_observation;

    DROP FUNCTION IF EXISTS register_alighting_evaluation_work();
    DROP FUNCTION IF EXISTS register_alighting_settlement_batch();
    DROP FUNCTION IF EXISTS cleanup_alighting_settlement_batch();
    DROP FUNCTION IF EXISTS track_alighting_batch_changes();
    DROP FUNCTION IF EXISTS track_alighting_observation_changes();
    DROP FUNCTION IF EXISTS invalidate_alighting_settlement(bigint[], timestamptz[]);
    DROP FUNCTION IF EXISTS enqueue_alighting_settlement_batch(bigint, varchar, timestamptz);

    DROP VIEW IF EXISTS
        alighting_evaluation_status, alighting_quality_eligible_metric, alighting_quality_eligible_evaluation,
        alighting_forecast_evaluation_metric, training_eligible_seat_forecast, one_stop_operational_seat_forecast,
        forecast_evaluation, forecast_evaluation_pending, training_eligible_stop_demand_statistics;

    DROP TABLE IF EXISTS
        alighting_settlement_pass, alighting_settlement_observation, alighting_settlement_projection,
        alighting_settlement_batch_work, alighting_settlement_generation, alighting_settlement_queue_scan,
        alighting_forecast_evaluation_result, alighting_evaluation_archive, alighting_forecast_evaluation_work,
        forecast_evaluation_result, forecast_evaluation_compact, forecast_evaluation_work,
        archive_legacy_member, archive_legacy_batch, alighting_history_checkpoint,
        seat_forecast, forecast_publication,
        stop_demand_statistics, file_statistics_publication, demand_statistics_version,
        model_training_exclusion, statistics_training_exclusion,
        operational_archive_file, operational_archive_job, operational_archive_boundary;
END $$;
