package com.gustler.backend.routecatalog.infrastructure.jdbc;

import com.gustler.backend.routecatalog.domain.RouteStop;
import com.gustler.backend.routecatalog.domain.RouteStopLocationStore;
import com.gustler.backend.routecatalog.domain.RouteStopLocationStore.Changes;
import com.gustler.backend.routecatalog.domain.RouteStops;
import com.gustler.backend.routecatalog.domain.StopCoordinates;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 호출자는 노선 행 잠금을 보유한다. 동일 좌표는 UPDATE도 하지 않는다. */
@Repository
@Transactional(readOnly = false, propagation = Propagation.MANDATORY)
public class JdbcRouteStopLocationStore implements RouteStopLocationStore {
    private final JdbcClient jdbc;
    private final JdbcTemplate batchJdbc;

    public JdbcRouteStopLocationStore(JdbcClient jdbc, JdbcTemplate batchJdbc) {
        this.jdbc = jdbc;
        this.batchJdbc = batchJdbc;
    }

    @Override
    public Changes record(final long versionId, RouteStops stops, OffsetDateTime observedAt) {
        List<Saved> rows = jdbc.sql("""
            SELECT DISTINCT ON (stop_order) stop_order, x, y, observed_at
            FROM route_stop_location_history WHERE route_version_id = ?
            ORDER BY stop_order, observed_at DESC
            """).param(versionId).query((rs, index) -> new Saved(rs.getInt("stop_order"),
            new StopCoordinates(rs.getDouble("x"), rs.getDouble("y")),
            rs.getObject("observed_at", OffsetDateTime.class))).list();
        Map<Integer, Saved> latest = new HashMap<>();
        rows.forEach(row -> latest.put(row.order(), row));
        List<Object[]> writes = new ArrayList<>();
        int initial = 0;
        int changed = 0;
        int missing = 0;
        for (RouteStop stop : stops.stops()) {
            StopCoordinates coordinates = stop.coordinates();
            if (coordinates == null) {
                missing++;
                continue;
            }
            Saved previous = latest.get(stop.stopOrder());
            if (previous != null && previous.coordinates().equals(coordinates)) {
                continue;
            }
            if (previous != null && !observedAt.isAfter(previous.observedAt())) {
                throw new IllegalArgumentException("이미 저장한 좌표보다 오래된 변경이다");
            }
            writes.add(
                new Object[] {versionId, stop.stopOrder(), stop.stopId(), observedAt, coordinates.x(),
                    coordinates.y()});
            if (previous == null) {
                initial++;
                continue;
            }
            changed++;
        }
        if (!writes.isEmpty()) {
            batchJdbc.batchUpdate("""
                INSERT INTO route_stop_location_history
                    (route_version_id, stop_order, stop_id, observed_at, x, y) VALUES (?, ?, ?, ?, ?, ?)
                """, writes);
        }
        return new Changes(initial, changed, missing);
    }

    private record Saved(int order, StopCoordinates coordinates, OffsetDateTime observedAt) {
    }
}
