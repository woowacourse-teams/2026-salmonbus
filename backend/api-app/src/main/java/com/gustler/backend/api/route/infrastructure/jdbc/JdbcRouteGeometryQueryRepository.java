package com.gustler.backend.api.route.infrastructure.jdbc;

import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.application.RouteGeometryQueryRepository;
import com.gustler.backend.api.route.domain.RouteGeometry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcRouteGeometryQueryRepository implements RouteGeometryQueryRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcRouteGeometryQueryRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override
    public Optional<Reference> reference(String routeId) {
        return jdbc.sql("""
            SELECT route.public_route_id,version.id,route.display_name,route.start_stop_name,route.end_stop_name,version.turn_sequence
            FROM route JOIN route_version version ON version.route_id=route.id AND version.valid_to IS NULL
            WHERE route.public_route_id=? AND route.source_id='GBIS'
            """).param(routeId).query((row, index) -> new Reference(row.getString(1), row.getLong(2), row.getString(3),
                row.getString(4), row.getString(5), row.getObject(6, Integer.class))).optional();
    }

    @Override
    public List<RouteGeometry.Stop> stops(long versionId) {
        return jdbc.sql("""
            SELECT stop.stop_id,stop.stop_order,stop.name,stop.direction,stop.boarding_allowed,location.x,location.y
            FROM route_stop stop LEFT JOIN LATERAL (
                SELECT x,y FROM route_stop_location_history history
                WHERE history.route_version_id=stop.route_version_id AND history.stop_order=stop.stop_order
                ORDER BY history.observed_at DESC LIMIT 1
            ) location ON true WHERE stop.route_version_id=? ORDER BY stop.stop_order
            """).param(versionId).query((row, index) -> new RouteGeometry.Stop(row.getString(1), row.getInt(2), row.getString(3),
                row.getString(4), row.getBoolean(5), RouteGeometry.Point.orNull(row.getObject(6, Double.class),
                    row.getObject(7, Double.class)))).list();
    }

    @Override
    public Optional<RouteGeometry.Shape> shape(long versionId) {
        return jdbc.sql("""
            SELECT id,received_at,points::text FROM route_geometry WHERE route_version_id=?
            ORDER BY received_at DESC,id DESC LIMIT 1
            """).param(versionId).query((row, index) -> {
                try {
                    return new RouteGeometry.Shape(row.getObject(1, UUID.class), row.getTimestamp(2).toInstant(),
                        json.readValue(row.getString(3), new TypeReference<List<RouteGeometry.Point>>() { }));
                } catch (RuntimeException unreadable) { throw new ServiceUnavailableException(); }
            }).optional();
    }
}
