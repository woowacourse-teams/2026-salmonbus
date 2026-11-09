package com.gustler.backend.maintenance;

import com.gustler.backend.maintenance.db.DatabaseConnections;
import com.gustler.backend.routegeometry.api.RouteInputProvenance;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Explicit recovery of a failed current job; reading geometry never grants another retry budget. */
final class RouteDisplayRetryCommand {
    static final String APPROVAL_ACTION = "ACADEMY_ROUTE_DISPLAY_RETRY";
    static final int RETRY_BUDGET = 5;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    RouteDisplayRetryCommand(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    static Object run(CliArguments args) {
        String routeId = args.required("route-id"), key = args.required("generation-key");
        validateIdentity(routeId, key);
        var environment = DatabaseEnvironment.load(Configuration.load(args.requiredPath("config")));
        requireApproval(args, environment, routeId, key, Clock.systemUTC());
        var source = DatabaseConnections.dataSource(environment);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> new RouteDisplayRetryCommand(
                JdbcClient.create(source), new ObjectMapper(), Clock.systemUTC()).retry(routeId, key));
    }

    static void requireApproval(CliArguments args, DatabaseEnvironment environment,
                                String routeId, String key, Clock clock) {
        if (environment.targetKind() != DatabaseEnvironment.TargetKind.LOCAL) {
            new ApprovalGate(clock).require(args.requiredPath("approval"), APPROVAL_ACTION,
                    approvalFingerprint(routeId, key), environment.identitySha256());
        }
    }

    static String approvalFingerprint(String routeId, String key) {
        validateIdentity(routeId, key);
        return Sha256.of("route-display-retry\n" + routeId + "\n" + key + "\n" + RETRY_BUDGET);
    }

    Object retry(String routeId, String key) {
        validateIdentity(routeId, key);
        // Publication uses this same route -> job lock order.
        jdbc.sql("SELECT id FROM route WHERE source_id='GBIS' AND public_route_id=? FOR UPDATE")
                .param(routeId).query(Long.class).optional()
                .orElseThrow(() -> error("ROUTE_NOT_FOUND"));
        var job = jdbc.sql("""
                SELECT status,input_digest,evidence_revision,algorithm_revision,input_json::text,
                       source_manifest_id,attempts,attempt_limit
                FROM route_display_generation_job WHERE generation_key=? AND route_id=? FOR UPDATE
                """).params(key, routeId).query((r, n) -> new Job(r.getString(1), r.getString(2),
                        r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getInt(7), r.getInt(8)))
                .optional().orElseThrow(() -> error("ROUTE_DISPLAY_JOB_NOT_FOUND"));
        require("FAILED".equals(job.status()), "ROUTE_DISPLAY_JOB_NOT_FAILED");
        require(List.of(AutomaticRouteDisplay.REVISION, RouteDisplayGeneration.SOURCE_REVISION)
                .contains(job.algorithm()), "ROUTE_DISPLAY_ALGORITHM_RETIRED");
        validateSnapshot(routeId, key, job);
        var current = RouteDisplayGeneration.at(current(routeId), clock.instant());
        require(job.inputDigest().equals(RouteDisplayGeneration.input(current).digest())
                        && job.evidenceRevision().equals(current.shape().evidence().revision())
                        && key.equals(RouteDisplayGeneration.key(job.inputDigest(), job.evidenceRevision(),
                                job.algorithm(), current.shape().sourceManifestId())),
                "ROUTE_DISPLAY_SOURCE_CHANGED");
        require(job.attempts() <= Integer.MAX_VALUE - RETRY_BUDGET,
                "ROUTE_DISPLAY_RETRY_BUDGET_EXHAUSTED");
        int limit = job.attempts() + RETRY_BUDGET;
        int changed = jdbc.sql("""
                UPDATE route_display_generation_job
                SET status='PENDING',attempt_limit=?,lease_token=NULL,lease_until=NULL,failed_at=NULL,
                    next_attempt_at=clock_timestamp(),updated_at=clock_timestamp()
                WHERE generation_key=? AND route_id=? AND status='FAILED'
                """).params(limit, key, routeId).update();
        require(changed == 1, "ROUTE_DISPLAY_JOB_NOT_FAILED");
        return Map.of("routeId", routeId, "generationKey", key, "status", "PENDING",
                "attempts", job.attempts(), "previousAttemptLimit", job.attemptLimit(),
                "attemptLimit", limit, "retryBudget", RETRY_BUDGET);
    }

    private void validateSnapshot(String routeId, String key, Job job) {
        final RouteGeometry snapshot;
        try {
            snapshot = json.readValue(job.input(), RouteGeometry.class);
            require(snapshot.shape() != null && routeId.equals(snapshot.routeId())
                            && job.inputDigest().equals(RouteDisplayGeneration.input(snapshot).digest())
                            && job.evidenceRevision().equals(snapshot.shape().evidence().revision())
                            && java.util.Objects.equals(job.manifestId(), snapshot.shape().sourceManifestId())
                            && key.equals(RouteDisplayGeneration.key(job.inputDigest(), job.evidenceRevision(),
                                    job.algorithm(), job.manifestId())),
                    "ROUTE_DISPLAY_JOB_INVALID");
        } catch (RuntimeException invalid) {
            throw new MaintenanceException("ROUTE_DISPLAY_JOB_INVALID", invalid);
        }
        if (job.manifestId() == null) return;
        var provenance = provenance(job.manifestId());
        require(snapshot.shape().evidenceEvaluatedAt() != null
                        && provenance.referenceVersionId() == snapshot.referenceVersionId()
                        && provenance.inputDigest().equals(job.inputDigest())
                        && provenance.evidence().at(snapshot.shape().evidenceEvaluatedAt())
                                .equals(snapshot.shape().evidence()),
                "ROUTE_DISPLAY_JOB_PROVENANCE_INVALID");
    }

    private RouteGeometry current(String routeId) {
        var route = jdbc.sql("""
                SELECT v.id,r.display_name,r.start_stop_name,r.end_stop_name,v.turn_sequence
                FROM route r JOIN route_version v ON v.route_id=r.id AND v.valid_to IS NULL
                WHERE r.source_id='GBIS' AND r.public_route_id=?
                """).param(routeId).query((r, n) -> new Reference(r.getLong(1), r.getString(2),
                        r.getString(3), r.getString(4), r.getObject(5, Integer.class))).single();
        var stops = jdbc.sql("""
                SELECT s.stop_id,s.stop_order,s.name,s.direction,s.boarding_allowed,l.x,l.y
                FROM route_stop s LEFT JOIN LATERAL (
                    SELECT x,y FROM route_stop_location_history h
                    WHERE h.route_version_id=s.route_version_id AND h.stop_order=s.stop_order
                    ORDER BY observed_at DESC LIMIT 1
                ) l ON true WHERE s.route_version_id=? ORDER BY s.stop_order
                """).param(route.version()).query((r, n) -> new RouteGeometry.Stop(r.getString(1), r.getInt(2),
                        r.getString(3), r.getString(4), r.getBoolean(5),
                        RouteGeometry.Point.orNull(r.getObject(6, Double.class), r.getObject(7, Double.class)))).list();
        var shape = jdbc.sql("""
                SELECT id,received_at,points::text,evidence::text,source_manifest_id FROM route_geometry
                WHERE route_version_id=? ORDER BY received_at DESC,id DESC LIMIT 1
                """).param(route.version()).query((r, n) -> new RouteGeometry.Shape(r.getObject(1, UUID.class),
                        r.getTimestamp(2).toInstant(),
                        json.readValue(r.getString(3), new TypeReference<List<RouteGeometry.Point>>() {}),
                        json.readValue(r.getString(4), RouteShapeEvidence.class), r.getString(5)))
                .optional().orElseThrow(() -> error("ROUTE_DISPLAY_SOURCE_NOT_READY"));
        var current = new RouteGeometry(routeId, route.version(), route.name(), route.start(), route.end(),
                route.turn(), stops, shape);
        if (shape.sourceManifestId() == null) return current;
        var provenance = provenance(shape.sourceManifestId());
        if (provenance.referenceVersionId() == current.referenceVersionId()
                && provenance.inputDigest().equals(RouteDisplayGeneration.input(current).digest())
                && provenance.evidenceRevision().equals(shape.evidence().revision())) return current;
        return new RouteGeometry(routeId, route.version(), route.name(), route.start(), route.end(), route.turn(),
                stops, new RouteGeometry.Shape(shape.id(), shape.receivedAt(), shape.points(), shape.evidence(),
                        null, null, shape.sourceManifestId()));
    }

    private RouteInputProvenance provenance(String id) {
        return jdbc.sql("""
                SELECT m.route_id,m.reference_version_id,m.input_digest,m.evidence_revision,
                       m.acquisition_manifest_id,m.document,r.public_route_id
                FROM route_input_manifest m
                JOIN route_version v ON v.id=m.reference_version_id AND v.route_id=m.route_id
                JOIN route r ON r.id=m.route_id WHERE m.manifest_id=?
                """).param(id).query((r, n) -> {
                    var document = r.getString(6);
                    var p = json.readValue(document, RouteInputProvenance.class);
                    p.verifyStored(document, id, r.getLong(1), r.getLong(2), r.getString(3),
                            r.getString(4), r.getString(5), r.getString(7));
                    return p;
                }).optional().orElseThrow(() -> error("ROUTE_DISPLAY_JOB_PROVENANCE_INVALID"));
    }

    private static void validateIdentity(String routeId, String key) {
        require(routeId != null && routeId.matches("[0-9]{9}"), "ROUTE_DISPLAY_ROUTE_ID_INVALID");
        require(key != null && key.matches("[0-9a-f]{64}"), "ROUTE_DISPLAY_GENERATION_KEY_INVALID");
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw error(code);
    }

    private static MaintenanceException error(String code) { return new MaintenanceException(code); }
    private record Job(String status, String inputDigest, String evidenceRevision, String algorithm,
                       String input, String manifestId, int attempts, int attemptLimit) {}
    private record Reference(long version, String name, String start, String end, Integer turn) {}
}
