package com.gustler.backend.api.route.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.api.route.domain.RouteGeometry.Point;
import com.gustler.backend.api.route.domain.RouteGeometry.Stop;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class RoutePathSplitterTest {
    private static final Point A = new Point(127, 37);
    private static final Point B = new Point(127.001, 37);
    private static final Point C = new Point(127.002, 37);
    private static final Point D = new Point(127.002, 37.001);
    private static final Point E = new Point(127, 37.001);

    @Test
    void 회차점을_기존_선상에_넣고_서로_다른_왕복_경로를_운행순서대로_보존한다() {
        var source = List.of(A, B, C, D, E);
        var geometry = geometry(2, List.of(A, new Point(127.0015, 37.00005), E), source);
        var paths = geometry.paths();

        assertThat(paths).isNotNull();
        var boundary = paths.up().points().getLast();
        assertThat(boundary.x()).isCloseTo(127.0015, within(1e-12));
        assertThat(boundary.y()).isEqualTo(37);
        assertThat(paths.up().points()).containsExactly(A, B, boundary);
        assertThat(paths.down().points()).containsExactly(boundary, C, D, E);
        assertThat(geometry.points()).isEqualTo(source);
    }

    @Test
    void 회차점이_기존_꼭짓점이면_불필요한_중복점을_추가하지_않는다() {
        var paths = geometry(2, List.of(A, B, C), List.of(A, B, C)).paths();
        assertThat(paths.up().points()).containsExactly(A, B);
        assertThat(paths.down().points()).containsExactly(B, C);
    }

    @Test
    void 원본의_길이0_선분과_반복좌표도_순서대로_남긴다() {
        var paths = geometry(2, List.of(A, B, C), List.of(A, A, B, B, C)).paths();
        var joined = new ArrayList<>(paths.up().points());
        joined.addAll(paths.down().points().subList(1, paths.down().points().size()));
        assertThat(joined).containsExactly(A, A, B, B, C);
    }

    @Test
    void 같은_좌표를_다시_지나도_앞선_방문순서로_올바른_회차_위치를_찾는다() {
        var beforeTurn = new Point(127.001, 37.001);
        var last = new Point(127.001, 36.999);
        var source = List.of(A, B, C, D, beforeTurn, B, last);
        var paths = geometry(4, List.of(A, C, beforeTurn, B, last), source).paths();
        assertThat(paths).isNotNull();
        assertThat(paths.up().points()).containsExactly(A, B, C, D, beforeTurn, B);
        assertThat(paths.down().points()).containsExactly(B, last);
    }

    @Test
    void 다른_회차_위치가_같은_방문자료에_똑같이_맞으면_임의로_고르지_않는다() {
        var source = List.of(A, B, C, D, E, A, B, C, D, E, A);
        var geometry = geometry(2, List.of(A, B, C), source);
        assertThat(geometry.paths()).isNull();
        assertThat(geometry.points()).isEqualTo(source);
        assertThat(geometry.status()).isEqualTo(RouteGeometry.Availability.READY);
    }

    @Test
    void 회차_밖의_일부_정류장좌표가_없어도_양쪽_방문근거가_있으면_분리한다() {
        var geometry = geometry(3, java.util.Arrays.asList(A, null, B, C), List.of(A, B, C));
        assertThat(geometry.status()).isEqualTo(RouteGeometry.Availability.PARTIAL);
        assertThat(geometry.paths().up().points()).containsExactly(A, B);
        assertThat(geometry.paths().down().points()).containsExactly(B, C);
    }

    @ParameterizedTest
    @MethodSource("unavailable")
    void 분리_근거가_없으면_전체형상을_유지하고_paths만_비운다(RouteGeometry geometry) {
        var original = geometry.points();
        assertThat(geometry.paths()).isNull();
        assertThat(geometry.points()).isEqualTo(original);
    }

    static Stream<RouteGeometry> unavailable() {
        return Stream.of(
            geometry(2, List.of(A, B, C), null),
            geometry(null, List.of(A, B, C), List.of(A, B, C)),
            geometry(2, java.util.Arrays.asList(A, null, C), List.of(A, B, C)),
            geometry(2, java.util.Arrays.asList(null, B, C), List.of(A, B, C)),
            geometry(1, List.of(A, B, C), List.of(A, B, C)),
            geometry(3, List.of(A, B, C), List.of(A, B, C)),
            geometry(2, List.of(A, new Point(128, 38), C), List.of(A, B, C))
        );
    }

    @Test
    void 큰_자료의_가공량을_제한하고_전체형상은_그대로_제공한다() {
        var stops = IntStream.range(0, 2001).mapToObj(i -> i % 2 == 0 ? A : B).toList();
        var points = IntStream.range(0, 1002).mapToObj(i -> i % 2 == 0 ? A : B).toList();
        var geometry = geometry(1000, stops, points);
        assertThat(geometry.paths()).isNull();
        assertThat(geometry.points()).hasSize(1002);
    }

    @Test
    void 실제_8노선의_회차_경계와_왕복_원본을_보존한다() throws Exception {
        // 저장 응답의 회차 주변 형상과 방문 순서를 확인한 선분 시작 lineSeq.
        var expectedEdges = Map.of("3330", 415, "1650", 524, "9007", 390, "9300", 398,
            "6011", 281, "3000", 371, "5600", 639, "3500", 479);
        try (var stream = getClass().getResourceAsStream("/gbis/route-geometry-eight-20261014.json")) {
            var routes = new ObjectMapper().readTree(stream).path("routes");
            assertThat(routes).hasSize(8);
            for (var route : routes) {
                var points = new ArrayList<Point>();
                route.path("shape").forEach(p -> points.add(new Point(p.path("x").doubleValue(), p.path("y").doubleValue())));
                var stops = new ArrayList<Point>();
                route.path("stops").forEach(s -> stops.add(new Point(s.path("x").doubleValue(), s.path("y").doubleValue())));
                String name = route.path("expectedName").asString();
                int turn = route.path("stops").get(0).path("turnSeq").intValue();
                var paths = geometry(turn, stops, points).paths();
                assertThat(paths).as(name).isNotNull();
                int edge = expectedEdges.get(name);
                Point boundary = paths.up().points().getLast();
                assertThat(paths.up().points().subList(0, edge)).as(name).isEqualTo(points.subList(0, edge));
                assertThat(paths.down().points().getFirst()).as(name).isEqualTo(boundary);
                assertThat(paths.down().points().subList(1, paths.down().points().size())).as(name)
                    .isEqualTo(points.subList(edge, points.size()));
                assertThat(paths.up().points()).as(name).hasSize(edge + 1);
                assertThat(paths.down().points()).as(name).hasSize(points.size() - edge + 1);
            }
        }
    }

    private static RouteGeometry geometry(Integer turn, List<Point> coordinates, List<Point> points) {
        var stops = IntStream.range(0, coordinates.size()).mapToObj(i -> new Stop("206000637", i + 1, "stop",
            turn == null || i + 1 <= turn ? "UP" : "DOWN", true, coordinates.get(i))).toList();
        return new RouteGeometry("204000057", 7, "3330", "A", "B", turn, stops,
            points == null ? null : new RouteGeometry.Shape(UUID.randomUUID(), Instant.EPOCH, points));
    }
}
