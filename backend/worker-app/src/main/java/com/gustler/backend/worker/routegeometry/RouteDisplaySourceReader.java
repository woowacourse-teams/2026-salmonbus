package com.gustler.backend.worker.routegeometry;

import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Reads the current source in the coordinator's transaction, never from a previous route version. */
@Component
public class RouteDisplaySourceReader {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public RouteDisplaySourceReader(JdbcClient jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public List<String> routes() {
        return jdbc.sql("SELECT public_route_id FROM route WHERE source_id='GBIS' ORDER BY public_route_id")
            .query(String.class).list();
    }
    public Optional<RouteGeometry> readLocked(String id) {
        var routes=jdbc.sql("""
            SELECT r.public_route_id,v.id,r.display_name,r.start_stop_name,r.end_stop_name,v.turn_sequence
            FROM route r JOIN route_version v ON v.route_id=r.id AND v.valid_to IS NULL
            WHERE r.source_id='GBIS' AND r.public_route_id=? FOR UPDATE OF r
            """).param(id).query((r,n) -> new RouteGeometry(r.getString(1),r.getLong(2),r.getString(3),
                r.getString(4),r.getString(5),r.getObject(6,Integer.class),List.of(),null)).optional();
        if (routes.isEmpty()) return Optional.empty();
        var r=routes.get();
        var stops=jdbc.sql("""
            SELECT s.stop_id,s.stop_order,s.name,s.direction,s.boarding_allowed,l.x,l.y
            FROM route_stop s LEFT JOIN LATERAL (
                SELECT x,y FROM route_stop_location_history h
                WHERE h.route_version_id=s.route_version_id AND h.stop_order=s.stop_order
                ORDER BY observed_at DESC LIMIT 1
            ) l ON true WHERE s.route_version_id=? ORDER BY s.stop_order
            """).param(r.referenceVersionId()).query((s,n) -> new RouteGeometry.Stop(s.getString(1),s.getInt(2),
                s.getString(3),s.getString(4),s.getBoolean(5),RouteGeometry.Point.orNull(
                    s.getObject(6,Double.class),s.getObject(7,Double.class)))).list();
        var shape=jdbc.sql("""
            SELECT id,received_at,points::text,evidence::text,source_manifest_id FROM route_geometry
            WHERE route_version_id=? ORDER BY received_at DESC,id DESC LIMIT 1
            """).param(r.referenceVersionId()).query((s,n) -> new RouteGeometry.Shape(s.getObject(1,UUID.class),
                s.getTimestamp(2).toInstant(),json.readValue(s.getString(3),new TypeReference<List<RouteGeometry.Point>>() {}),
                evidence(s.getString(4)),s.getString(5))).optional();
        var geometry=new RouteGeometry(r.routeId(),r.referenceVersionId(),r.name(),r.startPoint(),r.endPoint(),
            r.turnSequence(),stops,shape.orElse(null));
        return Optional.of(currentProvenance(geometry));
    }
    /** A new catalog snapshot may leave the old shape's receipt binding behind; never relabel it. */
    private RouteGeometry currentProvenance(RouteGeometry g) {
        if(g.shape()==null || g.shape().sourceManifestId()==null) return g;
        var manifest=manifest(g.shape().sourceManifestId());
        if(manifest.referenceVersionId()==g.referenceVersionId()
                && manifest.inputDigest().equals(com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration.input(g).digest())
                && manifest.evidenceRevision().equals(g.shape().evidence().revision())) return g;
        var s=g.shape();
        return new RouteGeometry(g.routeId(),g.referenceVersionId(),g.name(),g.startPoint(),g.endPoint(),g.turnSequence(),g.busStops(),
            new RouteGeometry.Shape(s.id(),s.receivedAt(),s.points(),s.evidence(),null,null,s.sourceManifestId()));
    }
    public boolean snapshotProvenanceMatches(RouteGeometry g,String expectedManifest) {
        if(g.shape()==null || !java.util.Objects.equals(g.shape().sourceManifestId(),expectedManifest)) return false;
        if(expectedManifest==null) return true;
        var m=manifest(expectedManifest);
        return m.referenceVersionId()==g.referenceVersionId()
            && m.inputDigest().equals(com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration.input(g).digest())
            && g.shape().evidenceEvaluatedAt()!=null
            && m.evidence().at(g.shape().evidenceEvaluatedAt()).equals(g.shape().evidence());
    }
    private com.gustler.backend.routegeometry.api.RouteInputProvenance manifest(String id) {
        return jdbc.sql("""
            SELECT m.route_id,m.reference_version_id,m.input_digest,m.evidence_revision,m.acquisition_manifest_id,m.document,r.public_route_id
            FROM route_input_manifest m JOIN route_version v ON v.id=m.reference_version_id AND v.route_id=m.route_id
            JOIN route r ON r.id=m.route_id WHERE m.manifest_id=?
            """).param(id).query((row,n) -> {
                String document=row.getString(6);
                var value=json.readValue(document,com.gustler.backend.routegeometry.api.RouteInputProvenance.class);
                value.verifyStored(document,id,row.getLong(1),row.getLong(2),row.getString(3),row.getString(4),row.getString(5),row.getString(7));
                return value;
            }).optional().orElseThrow(() -> new IllegalStateException("Input provenance missing or detached"));
    }

    private RouteShapeEvidence evidence(String text) {
        try { return java.util.Objects.requireNonNull(json.readValue(text,RouteShapeEvidence.class)); }
        catch (RuntimeException invalid) { return new RouteShapeEvidence("GBIS_API",
            List.of(new RouteShapeEvidence.Source("SUPPLEMENTARY_CACHE","INVALID",null)),List.of()); }
    }
}
