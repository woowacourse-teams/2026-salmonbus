package com.gustler.backend.api.route.application;

import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.QualityStatus;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class AutomaticRouteDisplayQualityTest {
    private RouteGeometry geometry(boolean far) {
        Point a = new Point(127, 37), b = new Point(127.001, 37), arrival = new Point(127, 37.0001);
        return new RouteGeometry(
                "204000057",
                1,
                "3330",
                "A",
                "B",
                2,
                List.of(
                        new Stop("A", 1, "A", "UP", true, far ? new Point(127, 37.0004) : a),
                        new Stop("B", 2, "B", "UP", true, b),
                        new Stop("A", 3, "A", "DOWN", true, arrival)),
                new Shape(
                        UUID.randomUUID(),
                        Instant.EPOCH,
                        List.of(
                                new Point(126.9995, 37),
                                a,
                                b,
                                new Point(127.001, 37.0001),
                                arrival,
                                new Point(126.9995, 37.0001))));
    }

    @Test
    void 기점종점_뒤의_짧은꼬리를_잘라_최종방문연결을_재계산한다() {
        var source = geometry(false);
        var original = source.paths();
        var result =
                AutomaticRouteDisplay.build(source, RouteDisplaySelector.input(source).digest());
        assertThat(result.quality().status()).isEqualTo(QualityStatus.PASSED);
        assertThat(result.paths().up().points().getFirst())
                .isEqualTo(source.busStops().getFirst().point());
        assertThat(result.paths().down().points().getLast())
                .isEqualTo(source.busStops().getLast().point());
        assertThat(result.paths().up().points().getLast())
                .isEqualTo(original.up().points().getLast());
        assertThat(result.paths().down().points().getFirst())
                .isEqualTo(original.down().points().getFirst());
        assertThat(result.connections().get(1).segmentIndex()).isZero();
        assertThat(result.connections().get(1).segmentFraction()).isZero();
        var last = result.connections().get(3);
        assertThat(last.segmentIndex()).isEqualTo(result.paths().down().points().size() - 2);
        assertThat(last.segmentFraction()).isEqualTo(1);
        assertThat(source.points()).hasSize(6);
    }

    @Test
    void 정류장에서_멀어진_자동선은_최신원본을_보존하며_표시를_보류한다() {
        var source = geometry(true);
        var result =
                AutomaticRouteDisplay.build(source, RouteDisplaySelector.input(source).digest());
        assertThat(result.quality().status()).isEqualTo(QualityStatus.WITHHELD);
        assertThat(result.quality().issues())
                .anySatisfy(
                        issue -> {
                            assertThat(issue.code()).isEqualTo("STOP_CONNECTION_GAP");
                            assertThat(issue.sequence()).isEqualTo(1);
                            assertThat(issue.meters()).isGreaterThan(30);
                        });
        assertThat(result.paths()).isNull();
        assertThat(result.connections()).isEmpty();
        assertThat(source.points()).hasSize(6);
        assertThat(source.paths()).isNotNull();
    }

    @Test
    void 너무긴_기점종점_꼬리를_근거없이_일괄삭제하지않는다() {
        var raw = geometry(false);
        var points = new ArrayList<>(raw.points());
        points.set(0, new Point(126.99, 37));
        var source =
                new RouteGeometry(
                        raw.routeId(),
                        1,
                        "3330",
                        "A",
                        "B",
                        2,
                        raw.busStops(),
                        new Shape(raw.shape().id(), Instant.EPOCH, points));
        var result =
                AutomaticRouteDisplay.build(source, RouteDisplaySelector.input(source).digest());
        assertThat(result.quality().status()).isEqualTo(QualityStatus.WITHHELD);
        assertThat(result.quality().issues())
                .anyMatch(issue -> issue.code().equals("TERMINAL_OVERHANG"));
        assertThat(source.points().getFirst().x()).isEqualTo(126.99);
    }
}
