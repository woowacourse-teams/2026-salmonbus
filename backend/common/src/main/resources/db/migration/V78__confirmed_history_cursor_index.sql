SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
CREATE INDEX ix_batch_confirmed_history
    ON observation_batch(route_version_id, input_confirmed_at, id)
    INCLUDE (response_received_at, stored_rows)
    WHERE ingestion_origin = 'LIVE' AND outcome = 'SUCCESS_ROWS' AND stored_rows > 0
      AND input_confirmed_at IS NOT NULL;
