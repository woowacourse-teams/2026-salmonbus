package com.gustler.backend.routecatalog.infrastructure.jdbc;

import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcRouteGeometryStore implements RouteGeometryStore {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcRouteGeometryStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Reference> current(String publicRouteId) {
        return jdbc.sql("""
            SELECT route.id,route.source_route_id,version.id FROM route
            JOIN route_version version ON version.route_id=route.id AND version.valid_to IS NULL
            WHERE route.public_route_id=? AND route.source_id='GBIS'
            """).param(publicRouteId).query((row, index) -> new Reference(row.getLong(1), row.getString(2), row.getLong(3))).optional();
    }

    @Override
    @Transactional
    public Publication publish(Reference reference, RouteGeometrySource.Read read) {
        // 카탈로그 변경과 같은 노선 행을 잠근다. 수집 도중 바뀐 판본에는 형상을 붙이지 않는다.
        jdbc.sql("SELECT id FROM route WHERE id=? FOR UPDATE").param(reference.routeId()).query(Long.class).single();
        boolean current = jdbc.sql("SELECT EXISTS(SELECT 1 FROM route_version WHERE id=? AND route_id=? AND valid_to IS NULL)")
            .params(reference.versionId(), reference.routeId()).query(Boolean.class).single();
        if (!current) return Publication.REFERENCE_CHANGED;
        var previous = jdbc.sql("""
            SELECT content_digest,received_at FROM route_geometry WHERE route_version_id=?
            ORDER BY received_at DESC,id DESC LIMIT 1
            """).param(reference.versionId()).query((row, index) ->
                new Previous(row.getString(1), row.getObject(2, java.time.OffsetDateTime.class))).optional();
        String digest = read.shape().digest();
        if (previous.isPresent() && (previous.get().digest().equals(digest)
            || !read.receivedAt().isAfter(previous.get().receivedAt()))) return Publication.UNCHANGED;
        jdbc.sql("""
            INSERT INTO route_geometry(id,route_version_id,received_at,content_digest,points)
            VALUES (?,?,?,?,CAST(? AS jsonb))
            """).params(UUID.randomUUID(), reference.versionId(), read.receivedAt(), digest,
                json.writeValueAsString(read.shape().points())).update();
        return Publication.UPDATED;
    }

    private record Previous(String digest, java.time.OffsetDateTime receivedAt) { }
}
