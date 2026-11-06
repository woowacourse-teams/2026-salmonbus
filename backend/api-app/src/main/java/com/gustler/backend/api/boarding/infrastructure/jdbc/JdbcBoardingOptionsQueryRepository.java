package com.gustler.backend.api.boarding.infrastructure.jdbc;

import com.gustler.backend.api.boarding.application.BoardingOptionsQueryRepository;
import com.gustler.backend.api.boarding.domain.StopVisit;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 현재 노선 판본과 방문·최신 좌표를 읽는다.
 */
@Repository
public class JdbcBoardingOptionsQueryRepository implements BoardingOptionsQueryRepository {
    private final JdbcClient jdbc;

    public JdbcBoardingOptionsQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> currentVersion(String routeId) {
        return jdbc.sql("""
            SELECT version.id FROM route JOIN route_version version ON version.route_id=route.id AND version.valid_to IS NULL
            WHERE route.public_route_id=? AND route.source_id='GBIS'
            """).param(routeId).query(Long.class).optional();
    }

    @Override
    public List<StopVisit> visits(final long versionId) {
        return jdbc.sql("""
            SELECT stop.stop_id,stop.stop_order,stop.name,stop.direction,stop.boarding_allowed,location.x,location.y
            FROM route_stop stop LEFT JOIN LATERAL (
                SELECT x,y FROM route_stop_location_history history
                WHERE history.route_version_id=stop.route_version_id AND history.stop_order=stop.stop_order
                ORDER BY history.observed_at DESC LIMIT 1
            ) location ON true WHERE stop.route_version_id=? ORDER BY stop.stop_order
            """).param(versionId).query((row, index) -> new StopVisit(row.getString(1), row.getInt(2),
            row.getString(3), row.getString(4), row.getBoolean(5), row.getObject(6, Double.class),
            row.getObject(7, Double.class))).list();
    }
}
