package com.gustler.backend.api.salmon.offline;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 판정 한 건의 자리: 노선(public_route_id), 정류장 방문, 판정 시각. */
record DecisionPoint(String routeId, String stationId, int stopSequence, Instant at) {
    private static final List<String> COLUMNS = List.of("route_id", "station_id", "stop_sequence", "decision_at");

    /** decision.csv(.gz)에 적힌 자리를 그대로 읽는다. decision_at은 ISO-8601 시각이다. */
    static List<DecisionPoint> fromFile(Path file) throws IOException {
        List<DecisionPoint> points = new ArrayList<>();
        try (CsvRows rows = CsvRows.open(file)) {
            if (!COLUMNS.equals(rows.next())) {
                throw new IllegalArgumentException(file.getFileName() + "의 첫 줄은 " + String.join(",", COLUMNS)
                    + "여야 한다");
            }
            for (List<String> row = rows.next(); row != null; row = rows.next()) {
                points.add(new DecisionPoint(row.get(0), row.get(1), Integer.parseInt(row.get(2)),
                    Instant.parse(row.get(3))));
            }
        }
        return points;
    }

    /**
     * 도착 스냅샷마다, 그 수신 시각에 그 정류장을 지나던 노선 방문 가운데 승차할 수 있는 방문마다 한 건씩 만든다.
     * 판정 시각은 스냅샷의 수신 시각이다. 스냅샷에 그 노선의 행이 없어도 만든다(도착 목록이 빈 판정이 된다).
     */
    static List<DecisionPoint> fromSnapshots(JdbcClient jdbc) {
        return jdbc.sql("""
            SELECT route.public_route_id, snapshot.station_id, stop.stop_order, snapshot.received_at
            FROM arrival_snapshot snapshot
            JOIN route_stop stop ON stop.stop_id = snapshot.station_id AND stop.boarding_allowed
            JOIN route_version version ON version.id = stop.route_version_id
                AND version.valid_from <= snapshot.received_at
                AND (version.valid_to IS NULL OR version.valid_to > snapshot.received_at)
            JOIN route route ON route.id = version.route_id AND route.source_id = 'GBIS'
            ORDER BY snapshot.received_at, snapshot.station_id, route.public_route_id, stop.stop_order
            """).query((row, index) -> new DecisionPoint(row.getString(1), row.getString(2), row.getInt(3),
            row.getTimestamp(4).toInstant())).list();
    }
}
