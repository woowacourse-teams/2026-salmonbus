package com.gustler.backend.forecasting.infrastructure.quality;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.quality.RouteDataQuality;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRouteDataQualityAccess implements RouteDataQualityAccess {
    private final JdbcClient jdbc;
    public JdbcRouteDataQualityAccess(final JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public long lock(final long version) {
        final long routeId = jdbc.sql("SELECT route_id FROM route_version WHERE id = ?")
            .param(version).query(Long.class).single();
        return lockByRoute(routeId);
    }

    @Override
    public long lockByRoute(final long routeId) {
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES (?) ON CONFLICT(route_id) DO NOTHING")
            .param(routeId).update();
        return jdbc.sql("SELECT quality_revision FROM route_data_quality WHERE route_id = ? FOR UPDATE")
            .param(routeId).query(Long.class).single();
    }

    @Override
    public void invalidate(final long version) {
        final var quality = jdbc.sql("""
            SELECT q.route_id, q.quality_revision FROM route_data_quality q
            JOIN route_version v ON v.route_id = q.route_id WHERE v.id = ? FOR UPDATE OF q
            """).param(version).query((rs, row) -> new RouteDataQuality(rs.getLong(1), rs.getLong(2))).single();
        quality.changeEligibility();
        jdbc.sql("UPDATE route_data_quality SET quality_revision = ? WHERE route_id = ?")
            .param(quality.revision()).param(quality.routeId()).update();
    }
}
