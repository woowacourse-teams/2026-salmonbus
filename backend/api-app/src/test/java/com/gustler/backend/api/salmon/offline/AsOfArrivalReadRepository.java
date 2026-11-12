package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 시계가 가리키는 시각에 유효하던 노선 판본의 방문과, 그 시각까지 수신한 가장 최근 도착 스냅샷을 읽는다.
 *
 * <p>제품 구현(JdbcArrivalReadRepository)과 같은 조회에 시각 조건만 더했다. 시계가 지금이면 두 구현은 같은 값을 돌려준다.
 */
final class AsOfArrivalReadRepository implements ArrivalReadRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    AsOfArrivalReadRepository(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public List<ArrivalVisit> visits(String stationId) {
        Timestamp at = Timestamp.from(clock.instant());
        return jdbc.sql("""
            SELECT route.public_route_id, route.source_route_id, version.id, stop.stop_order,
                   stop.direction, version.valid_from
            FROM route_stop stop JOIN route_version version ON version.id = stop.route_version_id
            JOIN route route ON route.id = version.route_id
            WHERE stop.stop_id = ? AND version.valid_from <= ? AND (version.valid_to IS NULL OR version.valid_to > ?)
              AND route.source_id = 'GBIS'
            ORDER BY route.public_route_id, stop.stop_order
            """).params(stationId, at, at).query((row, index) -> new ArrivalVisit(row.getString(1), row.getString(2),
            row.getLong(3), row.getInt(4), row.getString(5), row.getTimestamp(6).toInstant())).list();
    }

    @Override
    public Optional<ArrivalSnapshot> latest(String stationId) {
        return jdbc.sql("""
            SELECT payload::text FROM arrival_snapshot
            WHERE provider = ? AND operation = ? AND station_id = ? AND received_at <= ?
            ORDER BY received_at DESC, stored_at DESC LIMIT 1
            """).params(ArrivalSnapshot.PROVIDER, ArrivalSnapshot.OPERATION, stationId,
                Timestamp.from(clock.instant()))
            .query((row, index) -> json.readValue(row.getString(1), ArrivalSnapshot.class)).optional();
    }
}
