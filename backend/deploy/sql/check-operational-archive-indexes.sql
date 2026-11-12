-- 읽기 전용. 삭제에 필요한 FK 인덱스와 날짜 범위 조회용 인덱스를 확인한다.
WITH required(table_name, leading_column) AS (
    VALUES ('forecast_evaluation_result', 'arrival_observation_id'),
           ('forecast_evaluation_compact', 'arrival_observation_id'),
           ('seat_forecast', 'publication_id'),
           ('alighting_forecast_evaluation_result', 'pre_observation_id'),
           ('alighting_forecast_evaluation_result', 'post_observation_id'),
           ('alighting_forecast_evaluation_result', 'closure_observation_id'),
           ('alighting_forecast_evaluation_result', 'previous_post_observation_id'),
           ('alighting_forecast_evaluation_result', 'previous_closure_observation_id')
)
SELECT required.table_name, required.leading_column,
       COALESCE(bool_or(i.indisvalid AND i.indisready AND i.indpred IS NULL), false) AS ready,
       string_agg(i.indexrelid::regclass::text, ', ' ORDER BY i.indexrelid)
           FILTER (WHERE i.indexrelid IS NOT NULL) AS matching_indexes
FROM required
LEFT JOIN pg_attribute a ON a.attrelid = to_regclass(required.table_name) AND a.attname = required.leading_column
LEFT JOIN pg_index i ON i.indrelid = a.attrelid AND i.indkey[0] = a.attnum
GROUP BY required.table_name, required.leading_column
ORDER BY required.table_name, required.leading_column;

-- 실패한 CONCURRENTLY 인덱스는 동일 이름의 IF NOT EXISTS 재실행으로 복구되지 않는다.
SELECT i.indexrelid::regclass AS index_name, i.indisready, i.indisvalid, pg_get_indexdef(i.indexrelid) AS definition
FROM pg_index i
WHERE i.indrelid = 'observation_batch'::regclass
  AND i.indexrelid = to_regclass('ix_archive_batch_date');
