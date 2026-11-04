package com.gustler.backend.api.route.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.Quality;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.QualityStatus;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.Source;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Shape;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Stop;
import com.gustler.backend.routegeometry.engine.api.RoutePaths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class RouteGeometryResponseTest {
    private static final Point A = new Point(127, 37);
    private static final Point B = new Point(127.002, 37.002);
    private static final Point MID = new Point(127.001, 37.001);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 기본은_기존14필드와_정류장6필드에_연결만_더하고_선택경로와_원본을_보존한다() {
        var source = geometry();
        var selected = selected(source, QualityStatus.REVIEW_REQUIRED, connections());
        var result = RouteGeometryResponse.from(selected);
        var body = json.valueToTree(result);
        assertThat(body.size()).isEqualTo(14);
        assertThat(body.path("busStops").get(0).size()).isEqualTo(7);
        assertThat(body.has("displayPaths")).isFalse();
        assertThat(body.path("busStops").get(0).has("displayPoint")).isFalse();
        assertThat(result.paths().up().points()).containsExactly(
                new RouteGeometryResponse.Point(A.x(), A.y()),
                new RouteGeometryResponse.Point(MID.x(), MID.y()),
                new RouteGeometryResponse.Point(B.x(), B.y()));
        assertThat(result.points()).containsExactlyElementsOf(source.points().stream()
                .map(p -> new RouteGeometryResponse.Point(p.x(), p.y())).toList());
        assertThat(result.busStops().stream().map(RouteGeometryResponse.BusStop::point).toList())
                .containsExactlyElementsOf(source.busStops().stream()
                        .map(s -> new RouteGeometryResponse.Point(s.point().x(), s.point().y())).toList());
        assertThat(result.busStops()).extracting(RouteGeometryResponse.BusStop::stopSequence)
                .containsExactly(1, 2, 3);
        assertThat(result.busStops().get(2).stationId()).isEqualTo(result.busStops().get(0).stationId());
        assertThat(result.boundary().left()).isLessThanOrEqualTo(source.busStops().get(0).point().x());
    }

    @Test
    void 진단은_원본경로와_선택경로를_분리하고_기본과_다른_판본을_사용한다() {
        var source = selected(geometry(), QualityStatus.REVIEW_REQUIRED, connections());
        var normal = RouteGeometryResponse.from(source);
        var diagnostic = RouteGeometryResponse.from(source, true);
        assertThat(diagnostic.paths().up().points()).hasSize(2);
        assertThat(diagnostic.displayPaths().up().points()).hasSize(3);
        assertThat(diagnostic.displaySource()).isEqualTo("AUTOMATIC_FALLBACK");
        assertThat(diagnostic.displayQuality().status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(diagnostic.displayAccuracyGrade()).isEqualTo("UNEVALUATED");
        assertThat(diagnostic.geometryRevision()).isNotEqualTo(normal.geometryRevision());
        assertThat(normal.geometryRevision()).isNotEqualTo(source.revision());
        assertThat(normal.geometryRevision()).matches("[0-9a-f]{64}");
    }

    @Test
    void 표시보류는_원본선을_대신_노출하지않고_원본GPS만_보존한다() {
        var source = geometry().withDisplay(new RouteDisplayGeometry(null, Map.of(), Map.of(), "withheld",
                Source.AUTOMATIC_FALLBACK, List.of(), new Quality(QualityStatus.WITHHELD, List.of()),
                geometry().paths()));
        var normal = RouteGeometryResponse.from(source);
        assertThat(normal.status()).isEqualTo("UNAVAILABLE");
        assertThat(normal.reason()).isEqualTo("DISPLAY_WITHHELD");
        assertThat(normal.points()).isEmpty();
        assertThat(normal.paths()).isNull();
        assertThat(normal.busStops()).allMatch(s -> s.pathConnection() == null && s.point() != null);
        assertThat(json.valueToTree(normal).path("busStops").get(0).size()).isEqualTo(6);
        var diagnostic = RouteGeometryResponse.from(source, true);
        assertThat(diagnostic.points()).hasSize(3);
        assertThat(diagnostic.displayQuality().status()).isEqualTo("WITHHELD");
        assertThat(diagnostic.displayPaths()).isNull();
    }

    @Test
    void 생성대기는_최종보류와_다른사유지만_필드구조와_GPS보존은_같다() {
        var raw=geometry();
        var pending=raw.withDisplay(new RouteDisplayGeometry(null,Map.of(),Map.of(),"pending/key",Source.AUTOMATIC_FALLBACK,List.of(),
                new Quality(QualityStatus.WITHHELD,List.of(new RouteDisplayGeometry.QualityIssue("GENERATION_PENDING",null,null,null))),raw.paths()));
        var failed=raw.withDisplay(new RouteDisplayGeometry(null,Map.of(),Map.of(),"unavailable/key",Source.AUTOMATIC_FALLBACK,List.of(),
                new Quality(QualityStatus.WITHHELD,List.of(new RouteDisplayGeometry.QualityIssue("GENERATION_FAILED",null,null,null))),raw.paths()));
        var before=RouteGeometryResponse.from(pending);var after=RouteGeometryResponse.from(failed);
        assertThat(before.status()).isEqualTo("UNAVAILABLE");assertThat(before.reason()).isEqualTo("GENERATION_PENDING");
        assertThat(after.reason()).isEqualTo("DISPLAY_WITHHELD");assertThat(before.geometryRevision()).isNotEqualTo(after.geometryRevision());
        assertThat(json.valueToTree(before).size()).isEqualTo(14);assertThat(before.paths()).isNull();assertThat(before.points()).isEmpty();
        assertThat(before.busStops()).allMatch(stop->stop.point()!=null && stop.pathConnection()==null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "INDEX", "INTERPOLATION", "ORDER", "UNKNOWN_VISIT", "SEAM", "ZERO_LENGTH_ORDER"})
    void 손상된_선택묶음은_부분연결이나_원본경로와_혼합하지않는다(String fault) {
        var bad = new java.util.HashMap<>(connections());
        if (fault.equals("MISSING")) bad.remove(1);
        if (fault.equals("INDEX")) bad.put(1, connection(A, 99, 0));
        if (fault.equals("INTERPOLATION")) bad.put(1, connection(B, 0, 0));
        if (fault.equals("ORDER")) {
            bad.put(1, connection(B, 1, 1));
            bad.put(2, connection(A, 0, 0));
        }
        if (fault.equals("UNKNOWN_VISIT")) bad.put(9, connection(A, 0, 0));
        var source = selected(geometry(), QualityStatus.PASSED, bad);
        if (fault.equals("SEAM")) {
            var d = source.display();
            source = source.withDisplay(new RouteDisplayGeometry(new RoutePaths(d.paths().up(),
                    new RoutePaths.Path(List.of(MID, A))), d.connections(), d.stationPositions(),
                    d.revision(), d.source(), d.evidence(), d.quality(), d.sourcePaths()));
        }
        if (fault.equals("ZERO_LENGTH_ORDER")) {
            var d = source.display();
            source = source.withDisplay(new RouteDisplayGeometry(new RoutePaths(
                    new RoutePaths.Path(List.of(A, A, B)), d.paths().down()),
                    Map.of(1, connection(A, 1, 0), 2, connection(A, 0, 0), 3, connection(A, 0, 1)),
                    d.stationPositions(), d.revision(), d.source(), d.evidence(), d.quality(), d.sourcePaths()));
        }
        var normal = RouteGeometryResponse.from(source);
        assertThat(normal.reason()).isEqualTo("DISPLAY_WITHHELD");
        assertThat(normal.paths()).isNull();
        assertThat(normal.points()).isEmpty();
        assertThat(normal.busStops()).allMatch(s -> s.pathConnection() == null);
    }

    @Test
    void 미수집은_기존사유를_보존하고_분리불가_sourceOnly는_중립원본만_허용한다() {
        var raw = geometry();
        var missing = new RouteGeometry(raw.routeId(), raw.referenceVersionId(), raw.name(), raw.startPoint(),
                raw.endPoint(), raw.turnSequence(), raw.busStops(), null);
        assertThat(RouteGeometryResponse.from(missing).reason()).isEqualTo("NOT_COLLECTED");
        var unsplit = raw.withDisplay(new RouteDisplayGeometry(null, Map.of(), Map.of(), "source-only",
                Source.AUTOMATIC_FALLBACK, List.of(), new Quality(QualityStatus.SOURCE_ONLY, List.of())));
        var body = RouteGeometryResponse.from(unsplit);
        assertThat(body.status()).isEqualTo("READY");
        assertThat(body.points()).hasSize(3);
        assertThat(body.paths()).isNull();
        assertThat(body.busStops()).allMatch(s -> s.pathConnection() == null);
    }

    private static RouteGeometry geometry() {
        // Source stop A is offset from the path and remains an unchanged original GPS coordinate.
        return new RouteGeometry("204000057", 7, "3330", "A", "B", 2, List.of(
                new Stop("206000637", 1, "A", "UP", true, new Point(126.9999, 37)),
                new Stop("206000638", 2, "B", "UP", true, B),
                new Stop("206000637", 3, "A", "DOWN", true, A)),
                new Shape(UUID.fromString("00000000-0000-0000-0000-000000000001"), Instant.EPOCH,
                        List.of(A, B, A)));
    }
    private static RouteGeometry selected(RouteGeometry source, QualityStatus status, Map<Integer,Connection> c) {
        return source.withDisplay(new RouteDisplayGeometry(new RoutePaths(
                new RoutePaths.Path(List.of(A, MID, B)), new RoutePaths.Path(List.of(B, A))),
                c, Map.of(), "selected", Source.AUTOMATIC_FALLBACK, List.of(),
                new Quality(status, List.of()), source.paths()));
    }
    private static Map<Integer,Connection> connections() {
        return Map.of(1, connection(A, 0, 0), 2, connection(B, 1, 1), 3, connection(A, 0, 1));
    }
    private static Connection connection(Point p, int index, double t) {
        return new Connection(new com.gustler.backend.routegeometry.api.RouteDisplayInput.Point(p.x(),p.y()),index,t);
    }
}
