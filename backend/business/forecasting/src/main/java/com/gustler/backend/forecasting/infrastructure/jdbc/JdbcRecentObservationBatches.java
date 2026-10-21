package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.evaluation.RecentObservationBatches;
import com.gustler.backend.forecasting.application.evaluation.RecentObservationBatches.Header;
import com.gustler.backend.forecasting.application.evaluation.RecentObservationBatches.Row;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 관측 배치 헤더와 원천 행을 제한된 개수로 조회해 메모리 전달 누락의 복원 근거를 제공한다.
 */
@Repository
public class JdbcRecentObservationBatches implements RecentObservationBatches {
    private final JdbcClient jdbc;

    public JdbcRecentObservationBatches(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Header> headers(final long version, Instant after, final int limit) {
        return jdbc.sql("""
            SELECT b.id, b.attempt_number, b.response_received_at, COALESCE(b.stored_rows, -1) AS rows,
                   v.turn_sequence
            FROM observation_batch b
            JOIN route_version v ON v.id = b.route_version_id
            WHERE b.route_version_id = :version AND b.response_received_at > :after
            ORDER BY b.response_received_at, b.id LIMIT :limit
            """).param("version", version).param("after", after.atOffset(ZoneOffset.UTC))
            .param("limit", limit).query((rs, n) -> new Header(rs.getLong("id"),
                rs.getInt("attempt_number"), rs.getObject("response_received_at", OffsetDateTime.class).toInstant(),
                rs.getInt("rows"), rs.getObject("turn_sequence", Integer.class))).list();
    }

    @Override
    public List<Row> restore(final long version, List<Long> batches, final int limit) {
        if (batches.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
            SELECT o.observation_batch_id, o.id, o.vehicle_id, o.route_version_id, o.passed_stop_order,
                   b.response_received_at, o.remaining_seats, o.crowd_level,
                   CASE WHEN o.forecast_eligible THEN o.quality_direction END AS quality_direction,
                   o.id AS assessed_id
            FROM forecast_observation_quality o
            JOIN observation_batch b ON b.id = o.observation_batch_id AND b.route_version_id = o.route_version_id
            WHERE o.route_version_id = :version AND o.observation_batch_id IN (:batches)
            ORDER BY b.response_received_at, o.id LIMIT :limit
            """).param("version", version).param("batches", batches).param("limit", limit)
            .query((rs, n) -> new Row(rs.getLong("observation_batch_id"),
                JdbcArrivalObservationQuery.candidate(rs))).list();
    }
}
