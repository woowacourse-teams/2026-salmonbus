package com.gustler.backend.routeoperations.infrastructure.jdbc;

import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperationPreview;
import com.gustler.backend.routeoperations.api.RouteOperations;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DB is authoritative. Failed reads are propagated, never replaced by a stale ON snapshot. */
public class JdbcRouteOperations implements RouteOperations {
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public JdbcRouteOperations(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<RouteOperation> list() {
        return jdbcClient.sql("SELECT * FROM route_operation ORDER BY source_route_id")
            .query(this::map)
            .list();
    }

    @Override
    public Optional<RouteOperation> find(String routeId) {
        RouteOperation.requireRouteId(routeId);
        return jdbcClient.sql("SELECT * FROM route_operation WHERE source_route_id=?")
            .param(routeId)
            .query(this::map)
            .optional();
    }

    List<RouteOperation> readSnapshot(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM route_operation ORDER BY source_route_id"
        )) {
            statement.setQueryTimeout(5);
            try (ResultSet rows = statement.executeQuery()) {
                List<RouteOperation> operations = new ArrayList<>();
                while (rows.next()) {
                    operations.add(map(rows, 0));
                }
                return List.copyOf(operations);
            }
        }
    }

    Optional<RouteOperation> readSnapshot(Connection connection, String routeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM route_operation WHERE source_route_id=?"
        )) {
            statement.setQueryTimeout(5);
            statement.setString(1, routeId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return Optional.of(map(rows, 0));
                }
                return Optional.empty();
            }
        }
    }

    @Override
    public RouteOperation required(String routeId) {
        return find(routeId).orElseThrow(() -> new IllegalArgumentException("ROUTE_NOT_REGISTERED"));
    }

    @Override
    public RouteOperationPreview preview(String routeId) {
        RouteOperation.requireRouteId(routeId);
        RouteOperation operation = find(routeId).orElse(null);
        RuntimeStatus runtimeStatus =
            jdbcClient.sql(
                    "SELECT"
                        + " collection_enabled,forecast_enabled,maximum_collection_routes,reported_at,"
                        + " reported_at>CURRENT_TIMESTAMP-INTERVAL '60 seconds' AS"
                        + " fresh FROM route_operation_runtime WHERE singleton")
                .query(
                    (rs, rowNumber) ->
                        new RuntimeStatus(
                            rs.getBoolean("collection_enabled"),
                            rs.getBoolean("forecast_enabled"),
                            rs.getInt("maximum_collection_routes"),
                            rs.getObject("reported_at", OffsetDateTime.class)
                                .toInstant(),
                            rs.getBoolean("fresh")))
                .optional()
                .orElse(new RuntimeStatus(false, false, 0, null, false));
        final long requested =
            jdbcClient.sql("SELECT count(*) FROM route_operation WHERE collection_enabled")
                .query(Long.class)
                .single();
        Instant observation =
            jdbcClient.sql(
                    "SELECT max(b.response_received_at) FROM observation_batch b JOIN"
                        + " route_version v ON v.id=b.route_version_id JOIN route r ON"
                        + " r.id=v.route_id WHERE r.source_id='GBIS' AND"
                        + " r.source_route_id=? AND v.valid_to IS NULL AND b.outcome IN"
                        + " ('SUCCESS_ROWS','SUCCESS_EMPTY')")
                .param(routeId)
                .query((rs, rowNumber) -> nullableInstant(rs.getObject(1, OffsetDateTime.class)))
                .optional()
                .orElse(null);
        Instant publication =
            jdbcClient.sql(
                    "SELECT max(p.published_at) FROM forecast_publication p JOIN"
                        + " route_version v ON v.id=p.route_version_id JOIN route r ON"
                        + " r.id=v.route_id WHERE r.source_id='GBIS' AND"
                        + " r.source_route_id=? AND v.valid_to IS NULL")
                .param(routeId)
                .query((rs, rowNumber) -> nullableInstant(rs.getObject(1, OffsetDateTime.class)))
                .optional()
                .orElse(null);
        return new RouteOperationPreview(
            routeId,
            operation,
            operationState(operation),
            runtimeStatus.fresh(),
            runtimeStatus.collection(),
            runtimeStatus.forecast(),
            runtimeStatus.maximum(),
            requested,
            runtimeStatus.reportedAt(),
            observation,
            publication);
    }

    private String operationState(RouteOperation operation) {
        if (operation == null) {
            return "NOT_REGISTERED";
        }
        return operation.state();
    }

    private record RuntimeStatus(
        boolean collection, boolean forecast, int maximum, Instant reportedAt, boolean fresh
    ) {
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }

    @Override
    public RouteOperation register(String routeId, UUID request, String actor) {
        RouteOperation.requireRouteId(routeId);
        requireText(actor, 100);
        Objects.requireNonNull(request, "REQUEST_ID_REQUIRED");
        String payload = payloadHash(routeId, "REGISTER", actor);
        return transactionTemplate.execute(
            status -> {
                jdbcClient.sql("SELECT pg_advisory_xact_lock(7174001)").query(Object.class).single();
                if (find(routeId).isEmpty()) {
                    requireCollectionCapacity();
                    requireForecastWorker();
                    jdbcClient.sql(
                            "INSERT INTO"
                                + " route_operation(source_route_id,collection_enabled,service_requested)"
                                + " VALUES (?,true,true)")
                        .param(routeId)
                        .update();
                }
                RouteOperation operation = lock(routeId);
                if (duplicate(request, routeId, "REGISTER", payload)) {
                    return operation;
                }
                jdbcClient.sql(
                        "INSERT INTO"
                            + " route_operation_change(request_id,source_route_id,revision,action,actor,reason,payload_hash)"
                            + " VALUES (?,?,?,'REGISTER',?,'ONBOARDING',?)")
                    .params(request, routeId, operation.revision(), actor, payload)
                    .update();
                return operation;
            });
    }

    /**
     * A revision conflict cannot overwrite another operator's decision. All enables serialize with
     * the quota check.
     */
    @Override
    public RouteOperation change(
        String routeId,
        final long expected,
        UUID request,
        String actor,
        String action,
        final boolean enabled,
        String reason,
        String message,
        String source
    ) {
        validateChangeInput(actor, action, enabled, reason, message, source, request);
        String payload =
            payloadHash(
                routeId,
                Long.toString(expected),
                actor,
                action,
                Boolean.toString(enabled),
                reason,
                message,
                source);
        return transactionTemplate.execute(
            status -> {
                jdbcClient.sql("SELECT pg_advisory_xact_lock(7174001)").query(Object.class).single();
                RouteOperation operation = lock(routeId);
                if (duplicate(request, routeId, action, payload)) {
                    return operation;
                }
                if (operation.revision() != expected) {
                    throw new IllegalArgumentException("REVISION_CONFLICT");
                }
                if (action.equals("RETRY") && !operation.registrationState().equals("FAILED")) {
                    throw new IllegalArgumentException("REGISTRATION_RETRY_NOT_ALLOWED");
                }
                boolean collection = operation.collectionEnabled(), service = operation.serviceRequested();
                if (action.equals("COLLECTION")) {
                    collection = enabled;
                }
                if (action.equals("SERVICE")) {
                    service = enabled;
                }
                if (action.equals("DISABLE_BOTH")) {
                    collection = false;
                    service = false;
                }
                if (service && !collection) {
                    throw new IllegalArgumentException("COLLECTION_REQUIRED");
                }
                if (enablesUnregisteredRoute(operation, action, enabled, collection)) {
                    throw new IllegalArgumentException("REGISTRATION_NOT_READY");
                }
                if (collection && !operation.collectionEnabled()) {
                    requireCollectionCapacity();
                }
                if (action.equals("SERVICE") && enabled) {
                    requireForecastWorker();
                }
                if (enablesUnsupportedService(operation, action, enabled)) {
                    throw new IllegalArgumentException("MODEL_NOT_SUPPORTED");
                }
                // SERVICE ON always prepares a fresh publication; repeated requests cannot
                // expose an old board.
                final boolean prepare = action.equals("SERVICE") && enabled;
                jdbcClient.sql("""
                    UPDATE route_operation SET collection_enabled=?,service_requested=?,
                        service_enabled=CASE WHEN ? OR NOT ? THEN false ELSE service_enabled END,
                        enable_after=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE enable_after END,
                        registration_state=CASE WHEN ? THEN 'PENDING' ELSE registration_state END,
                        geometry_state=CASE WHEN ? THEN 'PENDING' ELSE geometry_state END,
                        reason=?,message=?,source_url=?,revision=revision+1,requested_at=CURRENT_TIMESTAMP,
                        last_error=CASE WHEN ? THEN '' ELSE last_error END WHERE source_route_id=?
                    """)
                    .params(
                        collection,
                        service,
                        prepare,
                        service,
                        prepare,
                        action.equals("RETRY"),
                        action.equals("RETRY_GEOMETRY"),
                        reason,
                        message,
                        source,
                        action.equals("RETRY"),
                        routeId)
                    .update();
                if (List.of("STRIKE", "PARTIAL", "MAINTENANCE").contains(reason) && !service) {
                    jdbcClient.sql(
                            "INSERT INTO"
                                + " route_service_event(source_route_id,reason,started_at,source_url)"
                                + " VALUES (?,?,CURRENT_TIMESTAMP,?) ON CONFLICT"
                                + " (source_route_id) WHERE ended_at IS NULL DO"
                                + " NOTHING")
                        .params(routeId, reason, source)
                        .update();
                }
                if (prepare) {
                    jdbcClient.sql(
                            "UPDATE route_service_event SET ended_at=CURRENT_TIMESTAMP"
                                + " WHERE source_route_id=? AND ended_at IS NULL")
                        .param(routeId)
                        .update();
                }
                jdbcClient.sql(
                        "INSERT INTO"
                            + " route_operation_change(request_id,source_route_id,revision,action,actor,reason,payload_hash)"
                            + " VALUES (?,?,?,?,?,?,?)")
                    .params(request, routeId, operation.revision() + 1, action, actor, reason, payload)
                    .update();
                if (prepare || List.of("STRIKE", "PARTIAL", "MAINTENANCE").contains(reason)) {
                    jdbcClient.sql(
                            "UPDATE route_data_quality q SET"
                                + " quality_revision=quality_revision+1 FROM route r"
                                + " WHERE r.id=q.route_id AND r.source_route_id=?")
                        .param(routeId)
                        .update();
                }
                return required(routeId);
            });
    }

    private void validateChangeInput(
        String actor,
        String action,
        final boolean enabled,
        String reason,
        String message,
        String source,
        UUID request
    ) {
        requireText(actor, 100);
        requireText(reason, 40);
        if (!List.of(
                "NORMAL",
                "MANUAL",
                "ONBOARDING",
                "PLANNED",
                "STRIKE",
                "PARTIAL",
                "MAINTENANCE",
                "RECOVERING")
            .contains(reason)) {
            throw new IllegalArgumentException("INVALID_REASON");
        }
        if (List.of("STRIKE", "PARTIAL").contains(reason) && (source == null || source.isBlank())) {
            throw new IllegalArgumentException("EVIDENCE_REQUIRED");
        }
        if (hasInvalidNotice(message, source)) {
            throw new IllegalArgumentException("INVALID_NOTICE");
        }
        if (!source.isEmpty()) {
            URI uri = URI.create(source);
            if (hasInvalidSource(uri)) {
                throw new IllegalArgumentException("INVALID_SOURCE_URL");
            }
        }
        if (!List.of("COLLECTION", "SERVICE", "DISABLE_BOTH", "RETRY", "RETRY_GEOMETRY", "NOTICE")
            .contains(action)) {
            throw new IllegalArgumentException("INVALID_ACTION");
        }
        if (action.equals("NOTICE") && !List.of("NORMAL", "MANUAL", "PLANNED").contains(reason)) {
            throw new IllegalArgumentException("INVALID_NOTICE_REASON");
        }
        if (isInvalidEnableReason(action, enabled, reason)) {
            throw new IllegalArgumentException("SERVICE_REASON_CONFLICT");
        }
        Objects.requireNonNull(request, "REQUEST_ID_REQUIRED");
    }

    private boolean hasInvalidNotice(String message, String source) {
        return message == null || message.length() > 500 || source == null || source.length() > 2000;
    }

    private boolean hasInvalidSource(URI source) {
        return !List.of("https", "http").contains(source.getScheme())
            || source.getHost() == null || source.getUserInfo() != null;
    }

    private boolean isInvalidEnableReason(String action, final boolean enabled, String reason) {
        return action.equals("SERVICE") && enabled && List.of("STRIKE", "PARTIAL").contains(reason);
    }

    private boolean enablesUnregisteredRoute(
        RouteOperation operation,
        String action,
        final boolean enabled,
        final boolean collection
    ) {
        return collection && !operation.registrationState().equals("READY")
            && enabled && List.of("COLLECTION", "SERVICE").contains(action);
    }

    private boolean enablesUnsupportedService(RouteOperation operation, String action, final boolean enabled) {
        return action.equals("SERVICE") && enabled && operation.lastError().equals("MODEL_NOT_SUPPORTED");
    }

    private void requireCollectionCapacity() {
        final long count =
            jdbcClient.sql("SELECT count(*) FROM route_operation WHERE collection_enabled")
                .query(Long.class)
                .single();
        final int budget =
            jdbcClient.sql("""
                SELECT maximum_collection_routes FROM route_operation_runtime
                WHERE singleton AND collection_enabled AND reported_at>CURRENT_TIMESTAMP-INTERVAL '60 seconds'
                """)
                .query(Integer.class)
                .optional()
                .orElse(0);
        if (budget < 1 || count >= budget) {
            throw new IllegalArgumentException("COLLECTION_BUDGET_EXCEEDED");
        }
    }

    private void requireForecastWorker() {
        final boolean ready =
            jdbcClient.sql("""
                SELECT EXISTS(SELECT 1 FROM route_operation_runtime WHERE singleton
                    AND collection_enabled AND forecast_enabled AND reported_at>CURRENT_TIMESTAMP-INTERVAL '60 seconds')
                """)
                .query(Boolean.class)
                .single();
        if (!ready) {
            throw new IllegalArgumentException("WORKER_NOT_READY");
        }
    }

    private boolean duplicate(
        UUID request,
        String routeId,
        String action,
        String payload
    ) {
        List<List<String>> rows =
            jdbcClient.sql(
                    "SELECT source_route_id,action,payload_hash FROM"
                        + " route_operation_change WHERE request_id=?")
                .param(request)
                .query(
                    (rs, rowNumber) ->
                        List.of(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list();
        if (rows.isEmpty()) {
            return false;
        }
        if (!rows.getFirst().equals(List.of(routeId, action, payload))) {
            throw new IllegalArgumentException("REQUEST_ID_REUSED");
        }
        return true;
    }

    private static String payloadHash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", impossible);
        }
    }

    @Override
    public void reportRuntime(final boolean collect, final boolean forecast, final int maximumRoutes) {
        jdbcClient.sql(
                "INSERT INTO"
                    + " route_operation_runtime(singleton,collection_enabled,forecast_enabled,maximum_collection_routes)"
                    + " VALUES(true,?,?,?) ON CONFLICT(singleton) DO UPDATE SET"
                    + " collection_enabled=EXCLUDED.collection_enabled,forecast_enabled=EXCLUDED.forecast_enabled,maximum_collection_routes=EXCLUDED.maximum_collection_routes,reported_at=CURRENT_TIMESTAMP")
            .params(collect, forecast, maximumRoutes)
            .update();
    }

    private RouteOperation lock(String routeId) {
        RouteOperation.requireRouteId(routeId);
        return jdbcClient.sql("SELECT * FROM route_operation WHERE source_route_id=? FOR UPDATE")
            .param(routeId)
            .query(this::map)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("ROUTE_NOT_REGISTERED"));
    }

    private String geometryState(final boolean ready) {
        if (ready) {
            return "READY";
        }
        return "FAILED";
    }

    private String registrationError(final boolean ready) {
        if (ready) {
            return "";
        }
        return "CATALOG_REFRESH_FAILED";
    }

    private String initialGeometryState(final boolean ready) {
        if (ready) {
            return "PENDING";
        }
        return "UNKNOWN";
    }

    private String modelError(final boolean supported) {
        if (supported) {
            return "";
        }
        return "MODEL_NOT_SUPPORTED";
    }

    @Override
    public void registrationResult(String routeId, final long revision, boolean ready) {
        jdbcClient.sql(
                "UPDATE route_operation SET"
                    + " registration_state=?,last_error=?,geometry_state=?,applied_revision=revision,applied_at=CURRENT_TIMESTAMP"
                    + " WHERE source_route_id=? AND revision=? AND"
                    + " registration_state='PENDING'")
            .params(
                geometryState(ready),
                registrationError(ready),
                initialGeometryState(ready),
                routeId,
                revision)
            .update();
    }

    @Override
    public void geometryResult(String routeId, final long revision, boolean ready) {
        jdbcClient.sql(
                "UPDATE route_operation SET geometry_state=? WHERE source_route_id=? AND"
                    + " revision=? AND registration_state='READY'")
            .params(geometryState(ready), routeId, revision)
            .update();
    }

    @Override
    public void modelResult(String routeId, final boolean supported) {
        jdbcClient.sql(
                "UPDATE route_operation SET last_error=?,service_enabled=CASE WHEN ? THEN"
                    + " service_enabled ELSE false END WHERE source_route_id=? AND"
                    + " registration_state='READY'")
            .params(modelError(supported), supported, routeId)
            .update();
    }

    @Override
    public void acknowledge(String routeId, final long revision) {
        jdbcClient.sql(
                "UPDATE route_operation SET applied_revision=?,applied_at=CURRENT_TIMESTAMP"
                    + " WHERE source_route_id=? AND revision=? AND applied_revision<>revision")
            .params(revision, routeId, revision)
            .update();
    }

    /**
     * Must run inside the publication transaction; excludes concurrent OFF until this commit
     * completes.
     */
    @Override
    public boolean allowPublication(final long routeVersion, Instant observedAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PUBLICATION_TRANSACTION_REQUIRED");
        }
        List<String> routeIds =
            jdbcClient.sql(
                    "SELECT r.source_route_id FROM route r JOIN route_version v ON"
                        + " v.route_id=r.id WHERE v.id=?")
                .param(routeVersion)
                .query(String.class)
                .list();
        if (routeIds.isEmpty()) {
            return false;
        }
        List<RouteOperation> rows =
            jdbcClient.sql("SELECT * FROM route_operation WHERE source_route_id=? FOR UPDATE")
                .param(routeIds.getFirst())
                .query(this::map)
                .list();
        if (rows.isEmpty()) {
            return false;
        }
        RouteOperation operation = rows.getFirst();
        return operation.canGenerate()
            && !observedAt.isBefore(operation.enableAfter())
            && !excluded(operation.routeId(), observedAt, observedAt);
    }

    @Override
    public void publicationCommitted(final long routeVersion, Instant observedAt) {
        jdbcClient.sql("""
            UPDATE route_operation o SET service_enabled=true,ever_published=true,applied_revision=revision,
                applied_at=CURRENT_TIMESTAMP,last_error='',
                reason=CASE WHEN NOT o.service_enabled AND o.reason<>'PLANNED' THEN 'NORMAL' ELSE o.reason END,
                message=CASE WHEN NOT o.service_enabled AND o.reason<>'PLANNED' THEN '' ELSE o.message END,
                source_url=CASE WHEN NOT o.service_enabled AND o.reason<>'PLANNED' THEN '' ELSE o.source_url END
            FROM route r JOIN route_version v ON v.route_id=r.id
            WHERE o.source_route_id=r.source_route_id AND v.id=? AND o.service_requested
                AND o.collection_enabled AND o.enable_after<=?
                AND ?>CURRENT_TIMESTAMP - CASE
                    WHEN EXTRACT(HOUR FROM (? AT TIME ZONE 'Asia/Seoul')) BETWEEN 1 AND 3
                    THEN INTERVAL '10 minutes' ELSE INTERVAL '5 minutes' END
            """)
            .params(routeVersion, offset(observedAt), offset(observedAt), offset(observedAt))
            .update();
    }

    @Override
    public boolean excluded(String routeId, Instant from, Instant until) {
        return jdbcClient.sql(
                "SELECT EXISTS(SELECT 1 FROM route_service_event WHERE source_route_id=? "
                    + "AND started_at<=? AND (ended_at IS NULL OR ended_at>?))")
            .params(routeId, offset(until), offset(from))
            .query(Boolean.class)
            .single();
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static void requireText(String value, final int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException("INVALID_OPERATION_TEXT");
        }
    }

    private RouteOperation map(ResultSet resultSet, final int rowNumber) throws SQLException {
        OffsetDateTime applied = resultSet.getObject("applied_at", OffsetDateTime.class);
        return new RouteOperation(
            resultSet.getString("source_route_id"),
            resultSet.getString("registration_state"),
            resultSet.getBoolean("collection_enabled"),
            resultSet.getBoolean("service_requested"),
            resultSet.getBoolean("service_enabled"),
            resultSet.getBoolean("ever_published"),
            resultSet.getString("reason"),
            resultSet.getString("message"),
            resultSet.getString("source_url"),
            resultSet.getLong("revision"),
            resultSet.getLong("applied_revision"),
            resultSet.getObject("enable_after", OffsetDateTime.class).toInstant(),
            resultSet.getString("last_error"),
            resultSet.getObject("requested_at", OffsetDateTime.class).toInstant(),
            nullableInstant(applied),
            resultSet.getString("geometry_state"));
    }
}
