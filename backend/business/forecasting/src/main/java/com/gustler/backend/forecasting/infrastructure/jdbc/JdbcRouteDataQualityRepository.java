package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.quality.RouteDataQuality;
import com.gustler.backend.forecasting.domain.quality.RouteDataQualityRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRouteDataQualityRepository implements RouteDataQualityRepository {
    private final JdbcClient jdbc;
    public JdbcRouteDataQualityRepository(final JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public RouteDataQuality findForUpdate(final long version) {
        return jdbc.sql("""
            SELECT q.route_id, q.quality_revision FROM route_data_quality q
            JOIN route_version v ON v.route_id = q.route_id WHERE v.id = ? FOR UPDATE OF q
            """).param(version).query((rs, row) -> new RouteDataQuality(rs.getLong(1), rs.getLong(2))).single();
    }

    @Override
    public void save(final RouteDataQuality quality) {
        jdbc.sql("UPDATE route_data_quality SET quality_revision = ? WHERE route_id = ?")
            .param(quality.revision()).param(quality.routeId()).update();
    }
}
