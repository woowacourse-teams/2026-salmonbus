package com.gustler.backend.maintenance;

import com.gustler.backend.routegeometry.api.RouteDisplaySamples;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

final class RouteDisplayRetryFixtures {
    static final Instant TIME = Instant.parse("2026-11-04T00:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();
    private RouteDisplayRetryFixtures() {}

    static RouteGeometry geometry(JdbcClient jdbc) {
        long version = jdbc.sql("""
                SELECT v.id FROM route r JOIN route_version v ON v.route_id=r.id AND v.valid_to IS NULL
                WHERE r.public_route_id='204000057'
                """).query(Long.class).single();
        var shape = jdbc.sql("""
                SELECT id,received_at,points::text,evidence::text,source_manifest_id FROM route_geometry
                WHERE route_version_id=? ORDER BY received_at DESC,id DESC LIMIT 1
                """).param(version).query((r, n) -> new RouteGeometry.Shape(r.getObject(1, UUID.class),
                        r.getTimestamp(2).toInstant(), JSON.readValue(r.getString(3),
                                new TypeReference<List<RouteGeometry.Point>>() {}),
                        JSON.readValue(r.getString(4), RouteShapeEvidence.class), r.getString(5))).single();
        var stops = RouteDisplaySamples.input().visits().stream().map(v -> new RouteGeometry.Stop(
                v.stationId(), v.sequence(), v.sequence()==2 ? "B" : "A", v.direction(), v.boardingAllowed(),
                new RouteGeometry.Point(v.sourcePoint().x(), v.sourcePoint().y()))).toList();
        return new RouteGeometry("204000057", version, "3330", "A", "B", 2, stops, shape);
    }

    static Seed failed(JdbcClient jdbc) {
        return failed(jdbc, geometry(jdbc), AutomaticRouteDisplay.REVISION, 5, 5, null);
    }

    static Seed failed(JdbcClient jdbc, RouteGeometry source, String algorithm,
                       int attempts, int limit, String overriddenJson) {
        var snapshot = RouteDisplayGeneration.at(source, TIME);
        String digest = RouteDisplayGeneration.input(snapshot).digest();
        String evidence = snapshot.shape().evidence().revision();
        String key = RouteDisplayGeneration.key(digest, evidence, algorithm, snapshot.shape().sourceManifestId());
        String input = overriddenJson == null ? JSON.writeValueAsString(snapshot) : overriddenJson;
        jdbc.sql("""
                INSERT INTO route_display_generation_job
                  (generation_key,route_id,input_digest,evidence_revision,algorithm_revision,input_json,
                   source_manifest_id,status,attempts,attempt_limit,last_failure_code,last_failure_at,failed_at)
                VALUES (?,'204000057',?,?,?,CAST(? AS jsonb),?,'FAILED',?,?,'EXECUTION_ERROR',?,?)
                """).params(key, digest, evidence, algorithm, input, snapshot.shape().sourceManifestId(),
                        attempts, limit, java.sql.Timestamp.from(TIME), java.sql.Timestamp.from(TIME)).update();
        return new Seed(key, snapshot, input);
    }

    record Seed(String key, RouteGeometry snapshot, String document) {}
}
