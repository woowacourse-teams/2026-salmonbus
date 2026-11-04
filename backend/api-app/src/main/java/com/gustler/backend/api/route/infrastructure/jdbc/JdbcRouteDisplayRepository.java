package com.gustler.backend.api.route.infrastructure.jdbc;

import com.gustler.backend.api.route.application.RouteDisplayRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Repository
public class JdbcRouteDisplayRepository implements RouteDisplayRepository {
    private final JdbcClient jdbc;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    public JdbcRouteDisplayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Manual> findManual(String routeId, String inputDigest) {
        return jdbc.sql(
                        """
SELECT display.document::text, display.content_digest FROM route_display_geometry display
JOIN route ON route.id=display.route_id
WHERE route.public_route_id=? AND route.source_id='GBIS'
  AND display.input_digest=? AND display.disabled_at IS NULL
ORDER BY display.registered_at DESC,display.id DESC LIMIT 1
""")
                .params(routeId, inputDigest)
                .query((row, index) -> new Manual(row.getString(1), row.getString(2)))
                .optional();
    }
    @Override
    public Optional<Automatic> findAutomatic(String generationKey, String routeId, String inputDigest,
            String evidenceRevision, String algorithmRevision) {
        return jdbc.sql("""
            SELECT document,document_sha256,source_manifest_id FROM route_display_automatic_artifact
            WHERE generation_key=? AND route_id=? AND input_digest=?
              AND evidence_revision=? AND algorithm_revision=?
            """).params(generationKey, routeId, inputDigest, evidenceRevision, algorithmRevision)
            .query((row,index) -> new Automatic(row.getString(1),row.getString(2),row.getString(3))).optional();
    }
    @Override
    public Map<String, GenerationState> findGenerationStates(String routeId, String inputDigest,
            String evidenceRevision, List<String> generationKeys) {
        if (generationKeys.isEmpty()) return Map.of();
        var rows = jdbc.sql("""
            SELECT generation_key,status,
                CASE WHEN status IN ('PENDING','SUPERSEDED') THEN attempts < attempt_limit
                     WHEN status='RUNNING' THEN lease_until > clock_timestamp() OR attempts < attempt_limit
                     ELSE false END AS can_progress
            FROM route_display_generation_job
            WHERE route_id=:routeId AND input_digest=:inputDigest AND evidence_revision=:evidenceRevision AND generation_key IN (:keys)
            """).param("routeId",routeId).param("inputDigest",inputDigest).param("evidenceRevision",evidenceRevision)
            .param("keys",generationKeys)
            .query((row,n) -> Map.entry(row.getString(1),new GenerationState(row.getString(2),row.getBoolean(3)))).list();
        var states = new HashMap<String, GenerationState>();
        rows.forEach(entry -> states.put(entry.getKey(),entry.getValue()));
        return Map.copyOf(states);
    }

    @Override
    public Optional<SourceManifest> findSourceManifest(String id) {
        return jdbc.sql("""
            SELECT m.route_id,m.reference_version_id,m.input_digest,m.evidence_revision,m.acquisition_manifest_id,m.document,r.public_route_id
            FROM route_input_manifest m JOIN route_version v ON v.id=m.reference_version_id AND v.route_id=m.route_id
            JOIN route r ON r.id=m.route_id WHERE m.manifest_id=?
            """).param(id).query((row,n) -> {
                String document=row.getString(6);
                var m=json.readValue(document,com.gustler.backend.routegeometry.api.RouteInputProvenance.class);
                m.verifyStored(document,id,row.getLong(1),row.getLong(2),row.getString(3),row.getString(4),row.getString(5),row.getString(7));
                return new SourceManifest(m.inputDigest(),m.evidenceRevision(),m.referenceVersionId());
            }).optional();
    }

}
