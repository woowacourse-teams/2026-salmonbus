package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 노선 품질 판본의 조회와 잠금을 제공한다.
 * 전체 조사와 차량별 조사 여부를 구분한다.
 */
@Repository
public class JdbcRouteDataQualityAccess implements RouteDataQualityAccess {
    private final JdbcClient jdbc;

    public JdbcRouteDataQualityAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long current(final long version) {
        return jdbc.sql("""
            SELECT COALESCE(q.quality_revision, 1)
            FROM route_version v
            LEFT JOIN route_data_quality q ON q.route_id = v.route_id
            WHERE v.id = ?
            """).param(version).query(Long.class).single();
    }

    @Override
    public void ensureExists(final long version) {
        jdbc.sql("""
            INSERT INTO route_data_quality(route_id)
            SELECT route_id FROM route_version WHERE id = ?
            ON CONFLICT(route_id) DO NOTHING
            """).param(version).update();
    }

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
    public boolean anyInvestigationPending(final long version) {
        return jdbc.sql(
        "SELECT EXISTS(SELECT 1 FROM trip_quality_rebuild WHERE route_version_id = ? AND NOT completed)")
            .param(version)
            .query(Boolean.class)
            .single();
    }

    @Override
    public boolean investigationPending(final long version, String vehicleId) {
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM trip_quality_rebuild
                WHERE route_version_id = ? AND NOT completed AND vehicle_id IN ('', ?))
            """).params(version, vehicleId).query(Boolean.class).single();
    }
}
