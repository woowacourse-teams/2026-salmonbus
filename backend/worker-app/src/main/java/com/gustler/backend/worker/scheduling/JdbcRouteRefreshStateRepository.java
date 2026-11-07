package com.gustler.backend.worker.scheduling;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Resolve a signal against the latest committed version before doing any upstream work. */
@Repository
public class JdbcRouteRefreshStateRepository {
    private final JdbcClient jdbc;

    public JdbcRouteRefreshStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record State(long routeId, String publicRouteId, String sourceRouteId, long versionId, boolean hasGeometry) {}

    public Optional<State> findByRouteId(long routeId) {
        return find("r.id=?", routeId);
    }

    public Optional<State> findByPublicId(String routeId) {
        return find("r.public_route_id=?", routeId);
    }

    public Optional<State> findBySourceId(String routeId) {
        return find("r.source_route_id=?", routeId);
    }

    private Optional<State> find(String predicate, Object value) {
        return jdbc.sql("""
            SELECT r.id,r.public_route_id,r.source_route_id,v.id,
                EXISTS(SELECT 1 FROM route_geometry g WHERE g.route_version_id=v.id)
            FROM route r JOIN route_version v ON v.route_id=r.id AND v.valid_to IS NULL
            WHERE r.source_id='GBIS' AND %s
            """.formatted(predicate)).param(value).query((r, n) ->
                new State(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4), r.getBoolean(5))).optional();
    }
}
