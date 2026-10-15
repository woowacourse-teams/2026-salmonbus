package com.gustler.backend.api.arrival.infrastructure.jdbc;

import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcArrivalReadRepository implements ArrivalReadRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcArrivalReadRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override
    public List<ArrivalVisit> visits(String stationId) {
        return jdbc.sql("""
            SELECT route.public_route_id, route.source_route_id, version.id, stop.stop_order,
                   stop.direction, version.valid_from
            FROM route_stop stop JOIN route_version version ON version.id = stop.route_version_id
            JOIN route route ON route.id = version.route_id
            WHERE stop.stop_id = ? AND version.valid_to IS NULL AND route.source_id = 'GBIS'
            ORDER BY route.public_route_id, stop.stop_order
            """).param(stationId).query((row, index) -> new ArrivalVisit(row.getString(1), row.getString(2),
                row.getLong(3), row.getInt(4), row.getString(5), row.getTimestamp(6).toInstant())).list();
    }

    @Override
    public Optional<ArrivalSnapshot> latest(String stationId) {
        return jdbc.sql("""
            SELECT payload::text FROM arrival_snapshot WHERE provider = ? AND operation = ? AND station_id = ?
            ORDER BY received_at DESC, stored_at DESC LIMIT 1
            """).params(ArrivalSnapshot.PROVIDER, ArrivalSnapshot.OPERATION, stationId)
            .query((row, index) -> json.readValue(row.getString(1), ArrivalSnapshot.class)).optional();
    }
}
