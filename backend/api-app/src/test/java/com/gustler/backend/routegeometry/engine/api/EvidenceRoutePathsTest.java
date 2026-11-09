package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.api.route.application.RouteDisplaySelector;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.*;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class EvidenceRoutePathsTest {
    private RouteGeometry geometry(boolean evidence, boolean mismatch) {
        var points =
                List.of(
                        new Point(127, 37),
                        new Point(127.0005, 37.00003),
                        new Point(127.001, 37),
                        new Point(127.001, 37.0001),
                        new Point(127, 37.0001));
        var typed = new ArrayList<RouteShapeEvidence.TypedPoint>();
        for (int i = 0; i < points.size(); i++) {
            var p = points.get(i);
            typed.add(
                    new RouteShapeEvidence.TypedPoint(
                            new RouteDisplayInput.Point(p.x() + (mismatch ? 0.1 : 0), p.y()),
                            i == 1 ? 2 : 1,
                            i <= 2 ? "UP" : "DOWN",
                            true));
        }
        var source =
                new RouteShapeEvidence(
                        "GBIS_API",
                        List.of(
                                new RouteShapeEvidence.Source(
                                        "GBIS_WEB", evidence ? "AVAILABLE" : "UNAVAILABLE", null)),
                        evidence ? typed : List.of());
        return new RouteGeometry(
                "204000057",
                1,
                "3330",
                "A",
                "B",
                2,
                List.of(
                        new Stop("A", 1, "A", "UP", true, points.getFirst()),
                        new Stop("B", 2, "B", "UP", true, points.get(2)),
                        new Stop("A", 3, "A", "DOWN", true, points.getLast())),
                new Shape(UUID.randomUUID(), Instant.EPOCH, points, source));
    }

    @Test
    void 모든자료가_없어도_원본의_회차와_양방향을_유지한다() {
        var geometry = geometry(false, false);
        var auto =
                AutomaticRouteDisplay.build(
                        geometry, RouteDisplaySelector.input(geometry).digest());
        assertThat(auto.paths()).isEqualTo(geometry.paths());
        assertThat(auto.connections()).containsKeys(1, 2, 3);
        assertThat(auto.stationPositions()).isEmpty();
    }

    @Test
    void 다른판본의_웹점열을_원본에_끼워맞추지않는다() {
        var geometry = geometry(true, true);
        assertThat(EvidenceRoutePaths.build(geometry)).isEqualTo(geometry.paths());
    }

    @Test
    void 대응이확인된_노드후보만_검사하고_회차_경계와_원본을_보존한다() {
        var geometry = geometry(true, false);
        var original = geometry.paths();
        var cleaned = EvidenceRoutePaths.build(geometry);
        assertThat(cleaned.up().points()).hasSize(original.up().points().size() - 1);
        assertThat(cleaned.up().points().getLast()).isEqualTo(original.up().points().getLast());
        assertThat(cleaned.down().points().getFirst())
                .isEqualTo(original.down().points().getFirst());
        assertThat(geometry.points()).hasSize(5);
        assertThat(
                        AutomaticRouteDisplay.build(
                                        geometry, RouteDisplaySelector.input(geometry).digest())
                                .connections())
                .containsKeys(1, 2, 3);
    }

    @Test
    void 정류장에_겹친_분류점은_제거하지않는다() {
        var geometry = geometry(true, false);
        var stops = new ArrayList<>(geometry.busStops());
        stops.set(0, new Stop("A", 1, "A", "UP", true, geometry.points().get(1)));
        var protectedGeometry =
                new RouteGeometry(
                        geometry.routeId(), 1, "3330", "A", "B", 2, stops, geometry.shape());
        assertThat(EvidenceRoutePaths.build(protectedGeometry))
                .isEqualTo(protectedGeometry.paths());
    }

    @Test
    void 보조근거가_바뀌면_자동캐시_판본이_바뀐다() {
        var raw = geometry(false, false);
        var withEvidence =
                new RouteGeometry(
                        raw.routeId(),
                        1,
                        "3330",
                        "A",
                        "B",
                        2,
                        raw.busStops(),
                        new Shape(
                                raw.shape().id(),
                                raw.shape().receivedAt(),
                                raw.points(),
                                geometry(true, false).shape().evidence()));
        var selector =
                new RouteDisplaySelector(
                        (id, digest) -> Optional.empty(),
                        new tools.jackson.databind.ObjectMapper(),
                        true);
        assertThat(selector.enrich(raw).revision())
                .isNotEqualTo(selector.enrich(withEvidence).revision());
        assertThat(RouteDisplaySelector.input(raw).digest())
                .isEqualTo(RouteDisplaySelector.input(withEvidence).digest());
    }

    @Test
    void 실제8노선_스냅샷에서_품질통과와_보류의_연결정책을_검증한다() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        try (var stream =
                getClass().getResourceAsStream("/gbis/route-geometry-eight-20261014.json")) {
            var routes = json.readTree(stream).path("routes");
            assertThat(routes).hasSize(8);
            List<Map<String, Object>> qualityReport = new ArrayList<>();
            for (var route : routes) {
                int turn = route.path("stops").get(0).path("turnSeq").asInt();
                List<Point> points = new ArrayList<>();
                route.path("shape")
                        .forEach(
                                value ->
                                        points.add(
                                                new Point(
                                                        value.path("x").asDouble(),
                                                        value.path("y").asDouble())));
                List<Stop> stops = new ArrayList<>();
                route.path("stops")
                        .forEach(
                                value -> {
                                    int sequence = value.path("stationSeq").asInt();
                                    stops.add(
                                            new Stop(
                                                    value.path("stationId").asText(),
                                                    sequence,
                                                    value.path("stationName").asText(),
                                                    sequence <= turn ? "UP" : "DOWN",
                                                    !value.path("stationId")
                                                            .asText()
                                                            .startsWith("277"),
                                                    Point.orNull(
                                                            value.path("x").asDouble(),
                                                            value.path("y").asDouble())));
                                });
                var geometry =
                        new RouteGeometry(
                                route.path("routeId").asText(),
                                1,
                                route.path("expectedName").asText(),
                                "A",
                                "B",
                                turn,
                                stops,
                                new Shape(UUID.randomUUID(), Instant.EPOCH, points));
                var display =
                        AutomaticRouteDisplay.build(
                                geometry, RouteDisplaySelector.input(geometry).digest());
                assertThat(geometry.points()).containsExactlyElementsOf(points);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("routeId", geometry.routeId());
                row.put("name", geometry.name());
                row.put("quality", display.quality());
                row.put("sourcePaths", geometry.paths());
                row.put("displayPaths", display.paths());
                row.put("stops", geometry.busStops());
                row.put("connections", display.connections());
                qualityReport.add(row);
                if (display.quality().status()
                        == com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.QualityStatus
                                .WITHHELD) {
                    assertThat(display.paths()).isNull();
                    assertThat(display.connections()).isEmpty();
                    assertThat(display.quality().issues()).isNotEmpty();
                    continue;
                }
                assertThat(display.paths().up().points().getLast())
                        .isEqualTo(geometry.paths().up().points().getLast());
                assertThat(display.connections()).hasSize(stops.size());
                Map<String, Double> progress = new HashMap<>();
                for (var stop : stops) {
                    var connection = display.connections().get(stop.stopSequence());
                    double at = connection.segmentIndex() + connection.segmentFraction();
                    assertThat(at)
                            .isGreaterThanOrEqualTo(progress.getOrDefault(stop.direction(), 0d));
                    progress.put(stop.direction(), at);
                    var path =
                            stop.direction().equals("UP")
                                    ? display.paths().up()
                                    : display.paths().down();
                    var a = path.points().get(connection.segmentIndex());
                    var b = path.points().get(connection.segmentIndex() + 1);
                    assertThat(connection.point().x())
                            .isCloseTo(
                                    a.x() + (b.x() - a.x()) * connection.segmentFraction(),
                                    within(1e-9));
                    assertThat(connection.point().y())
                            .isCloseTo(
                                    a.y() + (b.y() - a.y()) * connection.segmentFraction(),
                                    within(1e-9));
                }
            }
            var report = java.nio.file.Path.of("build/reports/route-display-quality-replay.json");
            java.nio.file.Files.createDirectories(report.getParent());
            java.nio.file.Files.writeString(
                    report,
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(qualityReport));
        }
    }

    @Test
    void worker가_멈춰도_조회시_만료된근거로_자동캐시를_재사용하지않는다() {
        var raw = geometry(true, false);
        var expiry = Instant.EPOCH.plusSeconds(3600);
        var evidence =
                new RouteShapeEvidence(
                        "GBIS_API",
                        List.of(
                                new RouteShapeEvidence.Source(
                                        "GG_BMS_NODES", "AVAILABLE", "a".repeat(64), expiry)),
                        raw.shape().evidence().webPoints());
        var geometry =
                new RouteGeometry(
                        raw.routeId(),
                        1,
                        "3330",
                        "A",
                        "B",
                        2,
                        raw.busStops(),
                        new Shape(raw.shape().id(), Instant.EPOCH, raw.points(), evidence));
        var clock = org.mockito.Mockito.mock(java.time.Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenReturn(Instant.EPOCH);
        var selector =
                new RouteDisplaySelector(
                        (id, digest) -> Optional.empty(),
                        new tools.jackson.databind.ObjectMapper(),
                        true,
                        Optional.of(clock));
        var before = selector.enrich(geometry);
        org.mockito.Mockito.when(clock.instant()).thenReturn(expiry);
        var after = selector.enrich(geometry);
        assertThat(before.revision()).isNotEqualTo(after.revision());
        assertThat(after.display().evidence().getFirst().status()).isEqualTo("STALE");
    }

    @Test
    void 노드제거가_반대방향과_새교차를_만들면_전체후보를_버린다() {
        List<Point> points =
                List.of(
                        new Point(127, 37),
                        new Point(127.0005, 37.0001),
                        new Point(127.001, 37),
                        new Point(127.0005, 37.00002),
                        new Point(127.0005, 36.99998),
                        new Point(127, 37));
        List<RouteShapeEvidence.TypedPoint> typed = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            typed.add(
                    new RouteShapeEvidence.TypedPoint(
                            new RouteDisplayInput.Point(point.x(), point.y()),
                            i == 1 ? 2 : 1,
                            i <= 2 ? "UP" : "DOWN",
                            true));
        }
        var evidence = new RouteShapeEvidence("GBIS_API", List.of(), typed);
        var geometry =
                new RouteGeometry(
                        "204000057",
                        1,
                        "3330",
                        "A",
                        "B",
                        2,
                        List.of(
                                new Stop("A", 1, "A", "UP", true, points.getFirst()),
                                new Stop("B", 2, "B", "UP", true, points.get(2)),
                                new Stop("A", 3, "A", "DOWN", true, points.getLast())),
                        new Shape(UUID.randomUUID(), Instant.EPOCH, points, evidence));
        assertThat(geometry.paths()).isNotNull();
        assertThat(EvidenceRoutePaths.build(geometry)).isEqualTo(geometry.paths());
    }

    @Test
    void 웹에_중복된_회차점만_원본한점에_양방향분류로_대응한다() {
        var raw = geometry(true, false);
        var list = new ArrayList<>(raw.shape().evidence().webPoints());
        var boundary = list.get(2);
        list.add(
                3,
                new RouteShapeEvidence.TypedPoint(
                        boundary.point(), boundary.type(), "DOWN", boundary.nodeConfirmed()));
        var source = withTyped(raw, list);
        var alignment = EvidenceRoutePaths.classify(source);
        assertThat(alignment.sharedBoundaryMerged()).isTrue();
        assertThat(alignment.matchedPoints()).isEqualTo(raw.points().size());
        assertThat(alignment.classifications().get(raw.points().get(2)))
                .extracting(value -> value.direction())
                .containsExactly("UP", "DOWN");
        assertThat(EvidenceRoutePaths.build(source).up().points())
                .hasSize(source.paths().up().points().size() - 1);
        assertThat(source.points()).isEqualTo(raw.points());
    }

    @Test
    void 임의로_추가된_웹점을_중복회차점으로_간주하지않는다() {
        var raw = geometry(true, false);
        var list = new ArrayList<>(raw.shape().evidence().webPoints());
        list.add(
                2,
                new RouteShapeEvidence.TypedPoint(
                        new RouteDisplayInput.Point(127.0009, 37.0004), 2, "UP", true));
        assertThat(EvidenceRoutePaths.classify(withTyped(raw, list)).classifications()).isEmpty();
        assertThat(EvidenceRoutePaths.build(withTyped(raw, list))).isEqualTo(raw.paths());
    }

    @Test
    void 부분미대응_곡선과_그이웃을_삭제하지않고_다른_확인구간만_분류한다() {
        List<Point> points = new ArrayList<>();
        List<RouteShapeEvidence.TypedPoint> typed = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Point point = new Point(127 + i * 0.00001, 37);
            points.add(point);
            typed.add(
                    new RouteShapeEvidence.TypedPoint(
                            new RouteDisplayInput.Point(
                                    point.x(), point.y() + (i == 30 ? 0.001 : 0)),
                            i == 29 ? 2 : 3,
                            i <= 94 ? "UP" : "DOWN",
                            true));
        }
        var source =
                new RouteGeometry(
                        "204000057",
                        1,
                        "3330",
                        "A",
                        "B",
                        2,
                        List.of(
                                new Stop("A", 1, "A", "UP", true, points.getFirst()),
                                new Stop("B", 2, "B", "UP", true, points.get(94)),
                                new Stop("C", 3, "C", "DOWN", true, points.getLast())),
                        new Shape(
                                UUID.randomUUID(),
                                Instant.EPOCH,
                                points,
                                new RouteShapeEvidence("GBIS_API", List.of(), typed)));
        var alignment = EvidenceRoutePaths.classify(source);
        assertThat(alignment.matchedPoints()).isEqualTo(99);
        assertThat(alignment.unmatchedPoints()).isEqualTo(1);
        assertThat(alignment.classifications()).doesNotContainKey(points.get(30));
        assertThat(EvidenceRoutePaths.build(source)).isEqualTo(source.paths());
        assertThat(EvidenceRoutePaths.build(source).up().points())
                .contains(points.get(29), points.get(30));
        assertThat(source.points()).containsExactlyElementsOf(points);
    }

    private RouteGeometry withTyped(RouteGeometry raw, List<RouteShapeEvidence.TypedPoint> typed) {
        var evidence = new RouteShapeEvidence("GBIS_API", raw.shape().evidence().sources(), typed);
        return new RouteGeometry(
                raw.routeId(),
                raw.referenceVersionId(),
                raw.name(),
                raw.startPoint(),
                raw.endPoint(),
                raw.turnSequence(),
                raw.busStops(),
                new Shape(raw.shape().id(), raw.shape().receivedAt(), raw.points(), evidence));
    }

    @Test
    void 한_보정이_새교차를_만들어도_다른_검증된_국소보정은_보존한다() {
        List<Point> points =
                List.of(
                        new Point(127, 37),
                        new Point(127.0005, 37.0001),
                        new Point(127.001, 37),
                        new Point(127.0015, 37.00003),
                        new Point(127.002, 37),
                        new Point(127.002, 37.0002),
                        new Point(127.0005, 37.00002),
                        new Point(127.0005, 36.99998),
                        new Point(127, 37));
        List<RouteShapeEvidence.TypedPoint> typed = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            typed.add(
                    new RouteShapeEvidence.TypedPoint(
                            new RouteDisplayInput.Point(point.x(), point.y()),
                            i == 1 || i == 3 ? 2 : 1,
                            i <= 4 ? "UP" : "DOWN",
                            true));
        }
        var source =
                new RouteGeometry(
                        "204000057",
                        1,
                        "3330",
                        "A",
                        "B",
                        2,
                        List.of(
                                new Stop("A", 1, "A", "UP", true, points.getFirst()),
                                new Stop("B", 2, "B", "UP", true, points.get(4)),
                                new Stop("A", 3, "A", "DOWN", true, points.getLast())),
                        new Shape(
                                UUID.randomUUID(),
                                Instant.EPOCH,
                                points,
                                new RouteShapeEvidence("GBIS_API", List.of(), typed)));
        var candidate = EvidenceRoutePaths.build(source);
        assertThat(candidate.up().points()).contains(points.get(1)).doesNotContain(points.get(3));
        assertThat(RouteDisplayTopology.noNewCrossings(source.paths(), candidate)).isTrue();
        assertThat(source.points()).hasSize(9);
    }
}
