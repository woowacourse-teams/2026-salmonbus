package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.RouteWorkAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRouteWorkAccess implements RouteWorkAccess {
    private final JdbcClient jdbc;

    public JdbcRouteWorkAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long current(final long version) {
        return jdbc.sql("""
            SELECT route.data_revision FROM route
            JOIN route_version version ON version.route_id = route.id WHERE version.id = ?
            """).param(version).query(Long.class).single();
    }

    @Override
    public long lock(final long version) {
        return jdbc.sql("""
            SELECT route.data_revision FROM route
            JOIN route_version version ON version.route_id = route.id WHERE version.id = ?
            FOR UPDATE OF route
            """).param(version).query(Long.class).single();
    }

    @Override
    public long lockByRoute(final long routeId) {
        return jdbc.sql("SELECT data_revision FROM route WHERE id = ? FOR UPDATE")
            .param(routeId).query(Long.class).single();
    }
}
