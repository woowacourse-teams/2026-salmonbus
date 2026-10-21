package com.gustler.backend.api.route.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.route.domain.RouteGeometry.Boundary;
import com.gustler.backend.api.route.domain.RouteGeometry.Point;
import com.gustler.backend.api.route.domain.RouteGeometry.Shape;
import com.gustler.backend.api.route.domain.RouteGeometry.Stop;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteGeometryTest {
    @Test
    void 미제공이나_축이_뒤바뀐_좌표를_지도_마커로_내보내지_않는다() {
        // when
        Point point1 = Point.orNull(null, 37.0);

        // then
        assertThat(point1).isNull();

        // when
        Point point2 = Point.orNull(0.0, 0.0);

        // then
        assertThat(point2).isNull();

        // when
        Point point3 = Point.orNull(37.0, 127.0);

        // then
        assertThat(point3).isNull();

        // when
        Point point4 = Point.orNull(Double.NaN, 37.0);

        // then
        assertThat(point4).isNull();
    }

    @Test
    void 표시영역은_도로_굴곡과_정류장_좌표를_모두_포함한다() {
        // when
        Shape shape = new Shape(UUID.randomUUID(), Instant.EPOCH, List.of(
            new Point(127.0, 37.0), new Point(127.1, 37.3),
            new Point(127.2, 37.0)));
        RouteGeometry map = new RouteGeometry("204000057", 7, "3330", "A", "B", null,
            List.of(new Stop("206000637", 1, "A", "UP", true, new Point(126.9, 37))), shape);

        // then
        assertThat(map.boundary()).isEqualTo(new Boundary(126.9, 37.3, 127.2, 37.0));
        assertThat(map.points()).hasSize(3);
    }
}
