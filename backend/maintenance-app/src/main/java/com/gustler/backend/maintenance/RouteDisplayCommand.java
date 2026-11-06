package com.gustler.backend.maintenance;

import com.gustler.backend.maintenance.db.DatabaseConnections;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteDisplayInput.Point;
import com.gustler.backend.routegeometry.api.RouteDisplayInput.Visit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 수동 검수 형상을 현재 원본에 검증해 등록한다. 공개 쓰기 HTTP 경로를 만들지 않는다. */
final class RouteDisplayCommand {
    private static final int MAX_BYTES = 8_000_000;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    RouteDisplayCommand(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    static Object run(CliArguments args) {
        DatabaseEnvironment environment =
                DatabaseEnvironment.load(Configuration.load(args.requiredPath("config")));
        byte[] artifact =
                args.command().equals("route-display-import")
                        ? read(args.requiredPath("artifact"))
                        : null;
        boolean write =
                args.command().equals("route-display-import")
                        || args.command().equals("route-display-disable");
        String routeId = artifact == null ? args.required("route-id") : null;
        if (write && environment.targetKind() != DatabaseEnvironment.TargetKind.LOCAL) {
            new ApprovalGate(Clock.systemUTC())
                    .require(
                            args.requiredPath("approval"),
                            artifact == null
                                    ? "ACADEMY_ROUTE_DISPLAY_DISABLE"
                                    : "ACADEMY_ROUTE_DISPLAY_IMPORT",
                            artifact == null ? Sha256.of(routeId) : Sha256.of(artifact),
                            environment.identitySha256());
        }
        var source = DatabaseConnections.dataSource(environment);
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(
                status -> {
                    RouteDisplayCommand command =
                            new RouteDisplayCommand(JdbcClient.create(source), new ObjectMapper());
                    return switch (args.command()) {
                        case "route-display-import" -> command.install(artifact);
                        case "route-display-disable" ->
                                command.disable(routeId, args.required("input-digest"));
                        case "route-display-export" -> command.export(routeId);
                        default -> command.status(routeId);
                    };
                });
    }

    Object install(byte[] bytes) {
        final ManualRouteDisplay document;
        try {
            document = json.readValue(bytes, ManualRouteDisplay.class);
        } catch (RuntimeException invalid) {
            throw new MaintenanceException("ROUTE_DISPLAY_ARTIFACT_INVALID", invalid);
        }
        // 수동 등록끼리는 동일 정류장 표시 위치 검사를 직렬화한다. 원천 갱신은 노선 잠금을 공유한다.
        jdbc.sql("SELECT pg_advisory_xact_lock(338338)").query(Object.class).optional();
        long route = lock(document.routeId());
        Snapshot snapshot = snapshot(document.routeId());
        try {
            document.validateFor(snapshot.input());
            sharedPositions(document);
        } catch (IllegalArgumentException invalid) {
            throw new MaintenanceException("ROUTE_DISPLAY_SOURCE_OR_CONNECTION_MISMATCH", invalid);
        }
        String revision = Sha256.of(bytes);
        jdbc.sql(
                        """
INSERT INTO route_display_geometry(route_id,input_digest,content_digest,document,registered_at)
VALUES (?,?,?,CAST(? AS jsonb),clock_timestamp())
ON CONFLICT(route_id,input_digest,content_digest) DO UPDATE
SET registered_at=clock_timestamp(),disabled_at=NULL
""")
                .params(
                        route,
                        document.inputDigest(),
                        revision,
                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                .update();
        return Map.of(
                "routeId",
                document.routeId(),
                "inputDigest",
                document.inputDigest(),
                "revision",
                revision,
                "source",
                "MANUAL_REVIEWED",
                "referenceVersionId",
                Long.toString(snapshot.version()));
    }

    Object disable(String routeId, String expected) {
        long route = lock(routeId);
        Snapshot snapshot = snapshot(routeId);
        if (!snapshot.input().digest().equals(expected)) {
            throw new MaintenanceException("ROUTE_DISPLAY_SOURCE_CHANGED");
        }
        int count =
                jdbc.sql(
                                "UPDATE route_display_geometry SET disabled_at=CURRENT_TIMESTAMP"
                                        + " WHERE route_id=? AND input_digest=? AND disabled_at IS"
                                        + " NULL")
                        .params(route, expected)
                        .update();
        return Map.of(
                "routeId",
                routeId,
                "inputDigest",
                expected,
                "disabled",
                count,
                "source",
                "AUTOMATIC_FALLBACK");
    }

    Object export(String routeId) {
        readLock(routeId);
        Snapshot snapshot = snapshot(routeId);
        return Map.of(
                "routeId",
                routeId,
                "referenceVersionId",
                Long.toString(snapshot.version()),
                "inputDigest",
                snapshot.input().digest(),
                "input",
                snapshot.input());
    }

    Object status(String routeId) {
        readLock(routeId);
        Snapshot snapshot = snapshot(routeId);
        String digest = snapshot.input().digest();
        var manual = selected(routeId, digest);
        boolean usable = false;
        if (manual.isPresent()) {
            try {
                json.readValue(manual.get(), ManualRouteDisplay.class)
                        .validateFor(snapshot.input());
                usable = true;
            } catch (RuntimeException invalid) {
                /* API와 같은 안전한 자동 대체 상태를 보고한다. */
            }
        }
        return Map.of(
                "routeId",
                routeId,
                "referenceVersionId",
                Long.toString(snapshot.version()),
                "inputDigest",
                digest,
                "source",
                usable ? "MANUAL_REVIEWED" : "AUTOMATIC_FALLBACK");
    }

    private void readLock(String routeId) {
        if (routeId == null || !routeId.matches("[0-9]{9}"))
            throw new MaintenanceException("ROUTE_DISPLAY_ROUTE_ID_INVALID");
        jdbc.sql("SELECT id FROM route WHERE public_route_id=? AND source_id='GBIS' FOR SHARE")
                .param(routeId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new MaintenanceException("ROUTE_NOT_FOUND"));
    }

    private long lock(String routeId) {
        if (!routeId.matches("[0-9]{9}")) {
            throw new MaintenanceException("ROUTE_DISPLAY_ROUTE_ID_INVALID");
        }
        return jdbc.sql(
                        "SELECT id FROM route WHERE public_route_id=? AND source_id='GBIS' FOR"
                                + " UPDATE")
                .param(routeId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new MaintenanceException("ROUTE_NOT_FOUND"));
    }

    private java.util.Optional<String> selected(String routeId, String digest) {
        return jdbc.sql(
                        """
SELECT display.document::text FROM route_display_geometry display JOIN route ON route.id=display.route_id
WHERE route.public_route_id=? AND route.source_id='GBIS' AND display.input_digest=? AND display.disabled_at IS NULL
ORDER BY display.registered_at DESC,display.id DESC LIMIT 1
""")
                .params(routeId, digest)
                .query(String.class)
                .optional();
    }

    private void sharedPositions(ManualRouteDisplay incoming) {
        Map<String, Point> positions = new HashMap<>();
        incoming.visits()
                .forEach(
                        visit -> {
                            if (visit.displayPoint() != null) {
                                positions.put(visit.stationId(), visit.displayPoint());
                            }
                        });
        List<String> routes =
                jdbc.sql(
                                """
SELECT DISTINCT route.public_route_id FROM route JOIN route_display_geometry display ON display.route_id=route.id
WHERE route.source_id='GBIS' AND route.public_route_id<>? AND display.disabled_at IS NULL
""")
                        .param(incoming.routeId())
                        .query(String.class)
                        .list();
        for (String route : routes) {
            Snapshot current;
            try {
                current = snapshot(route);
            } catch (MaintenanceException unavailable) {
                continue;
            }
            selected(route, current.input().digest())
                    .ifPresent(
                            text -> {
                                ManualRouteDisplay other =
                                        json.readValue(text, ManualRouteDisplay.class);
                                other.validateFor(current.input());
                                for (ManualRouteDisplay.Visit visit : other.visits()) {
                                    Point expected = positions.get(visit.stationId());
                                    // 핀 없는 경유 방문은 다른 노선의 실제 표시 핀과 충돌하지 않는다.
                                    if (expected != null
                                            && visit.displayPoint() != null
                                            && !expected.equals(visit.displayPoint())) {
                                        throw new IllegalArgumentException(
                                                "다른 노선의 검수 정류장 표시 위치와 다르다");
                                    }
                                }
                            });
        }
    }

    private Snapshot snapshot(String routeId) {
        var reference =
                jdbc.sql(
                                """
SELECT version.id,version.turn_sequence FROM route JOIN route_version version ON version.route_id=route.id AND version.valid_to IS NULL
WHERE route.public_route_id=? AND route.source_id='GBIS'
""")
                        .param(routeId)
                        .query(
                                (row, index) ->
                                        new Reference(
                                                row.getLong(1), row.getObject(2, Integer.class)))
                        .optional()
                        .orElseThrow(() -> new MaintenanceException("ROUTE_NOT_FOUND"));
        String points =
                jdbc.sql(
                                "SELECT points::text FROM route_geometry WHERE route_version_id=?"
                                        + " ORDER BY received_at DESC,id DESC LIMIT 1")
                        .param(reference.version())
                        .query(String.class)
                        .optional()
                        .orElseThrow(
                                () -> new MaintenanceException("ROUTE_DISPLAY_SOURCE_NOT_READY"));
        List<Visit> visits =
                jdbc.sql(
                                """
SELECT stop.stop_id,stop.stop_order,stop.direction,stop.boarding_allowed,location.x,location.y
FROM route_stop stop LEFT JOIN LATERAL (
    SELECT x,y FROM route_stop_location_history history
    WHERE history.route_version_id=stop.route_version_id AND history.stop_order=stop.stop_order
    ORDER BY history.observed_at DESC LIMIT 1
) location ON true WHERE stop.route_version_id=? ORDER BY stop.stop_order
""")
                        .param(reference.version())
                        .query(
                                (row, index) ->
                                        new Visit(
                                                row.getString(1),
                                                row.getInt(2),
                                                row.getString(3),
                                                row.getBoolean(4),
                                                point(
                                                        row.getObject(5, Double.class),
                                                        row.getObject(6, Double.class))))
                        .list();
        return new Snapshot(
                reference.version(),
                new RouteDisplayInput(
                        routeId,
                        reference.turnSequence(),
                        json.readValue(points, new TypeReference<List<Point>>() {}),
                        visits));
    }

    private static Point point(Double x, Double y) {
        if (x == null || y == null) {
            return null;
        }
        try {
            return new Point(x, y);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static byte[] read(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MaintenanceException("ROUTE_DISPLAY_ARTIFACT_FILE_INVALID");
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new MaintenanceException("ROUTE_DISPLAY_ARTIFACT_TOO_LARGE");
            }
            return bytes;
        } catch (IOException failure) {
            throw new MaintenanceException("ROUTE_DISPLAY_ARTIFACT_READ_FAILED", failure);
        }
    }

    private record Reference(long version, Integer turnSequence) {}

    private record Snapshot(long version, RouteDisplayInput input) {}
}
