package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.quality.WorkerQualityState.Snapshot;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 품질 판본과 조사 중인 차량 범위를 읽어 Worker 메모리의 재사용 판정 근거로 제공한다.
 */
public final class JdbcWorkerQualitySnapshotQuery {
    private final JdbcClient jdbc;

    public JdbcWorkerQualitySnapshotQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Snapshot read(final long version) {
        List<Row> rows = jdbc.sql("""
            SELECT /* salmonbus:worker_quality.select_snapshot */
                   COALESCE(q.quality_revision, 1) AS revision, r.vehicle_id
            FROM route_version v
            LEFT JOIN route_data_quality q ON q.route_id = v.route_id
            LEFT JOIN trip_quality_rebuild r
              ON r.route_version_id = v.id AND NOT r.completed
            WHERE v.id = ?
            """).param(version).query((rs, row) ->
            new Row(rs.getLong("revision"), rs.getString("vehicle_id"))).list();
        if (rows.isEmpty()) {
            throw new EmptyResultDataAccessException(1);
        }
        return new Snapshot(rows.getFirst().revision(), rows.stream()
                .map(Row::vehicle)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
    }

    private record Row(long revision, String vehicle) {
    }
}
