package com.gustler.backend.forecasting.infrastructure.quality;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
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
        jdbc.sql("SELECT id FROM route WHERE id = ? FOR UPDATE").param(routeId).query(Long.class).single();
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES (?) ON CONFLICT(route_id) DO NOTHING")
            .param(routeId).update();
        return jdbc.sql("SELECT quality_revision FROM route_data_quality WHERE route_id = ?")
            .param(routeId).query(Long.class).single();
    }

    @Override
    public boolean anyInvestigationPending(final long version) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM trip_quality_rebuild WHERE route_version_id = ? AND NOT completed)")
            .param(version).query(Boolean.class).single();
    }

    @Override
    public boolean investigationPending(final long version, final String vehicleId) {
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM trip_quality_rebuild
                WHERE route_version_id = ? AND NOT completed AND vehicle_id IN ('', ?))
            """).params(version, vehicleId).query(Boolean.class).single();
    }
}
