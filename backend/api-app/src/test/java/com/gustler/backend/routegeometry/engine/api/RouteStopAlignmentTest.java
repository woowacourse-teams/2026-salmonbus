package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Stop;

import org.junit.jupiter.api.Test;

import java.util.*;

class RouteStopAlignmentTest {
    private Point p(double x, double y) {
        return new Point(127 + x / 100000, 37 + y / 100000);
    }

    private Stop stop(String id, int sequence, Point point) {
        return new Stop(id, sequence, id, "UP", true, point);
    }

    @Test
    void 앞정류장의_가까운_나중구간을_선택해_뒤_방문을_망가뜨리지않는다() {
        var a = p(0, 0);
        var b = p(100, 0);
        var c = p(200, 0);
        var returned = p(100, 1);
        var result =
                RouteStopAlignment.align(
                        List.of(a, b, c, returned),
                        List.of(stop("B", 1, returned), stop("C", 2, c), stop("B", 3, returned)),
                        true);
        assertThat(result.usable()).isTrue();
        assertThat(
                        result.connections().get(1).segmentIndex()
                                + result.connections().get(1).segmentFraction())
                .isEqualTo(1);
        assertThat(result.connections().get(2).point().x()).isEqualTo(c.x());
        assertThat(
                        result.connections().get(3).segmentIndex()
                                + result.connections().get(3).segmentFraction())
                .isEqualTo(3);
    }

    @Test
    void 같은정류장의_반복방문을_각각의_루프진행도에_연결한다() {
        var a = p(0, 0);
        var b = p(100, 0);
        var c = p(100, 100);
        var result =
                RouteStopAlignment.align(
                        List.of(a, b, c, a),
                        List.of(stop("A", 1, a), stop("B", 2, b), stop("A", 3, a)),
                        true);
        assertThat(result.usable()).isTrue();
        assertThat(result.connections().get(1).segmentFraction()).isZero();
        assertThat(
                        result.connections().get(3).segmentIndex()
                                + result.connections().get(3).segmentFraction())
                .isEqualTo(3);
    }

    @Test
    void 같은좌표의_다른루프가_동일하게_맞으면_임의로_고르지않는다() {
        var a = p(0, 0);
        var b = p(100, 0);
        var c = p(200, 0);
        var result =
                RouteStopAlignment.align(
                        List.of(a, b, a, b, c), List.of(stop("A", 1, a), stop("C", 2, c)), true);
        assertThat(result.failure()).isEqualTo("AMBIGUOUS_STOP_ALIGNMENT");
        assertThat(result.connections()).isEmpty();
    }

    @Test
    void 방문_역행이나_큰이격을_선분끝으로_강제하지않는다() {
        var a = p(0, 0);
        var b = p(1000, 0);
        var c = p(2000, 0);
        assertThat(
                        RouteStopAlignment.align(
                                        List.of(a, b, c),
                                        List.of(stop("C", 1, c), stop("A", 2, a)),
                                        false)
                                .usable())
                .isFalse();
        assertThat(
                        RouteStopAlignment.align(
                                        List.of(a, b), List.of(stop("FAR", 1, p(0, 1000))), false)
                                .usable())
                .isFalse();
    }
}
