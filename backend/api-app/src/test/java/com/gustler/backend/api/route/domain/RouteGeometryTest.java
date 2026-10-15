package com.gustler.backend.api.route.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteGeometryTest {
    @Test
    void 미제공이나_축이_뒤바뀐_좌표를_지도_마커로_내보내지_않는다() {
        assertThat(RouteGeometry.Point.orNull(null, 37.0)).isNull();
        assertThat(RouteGeometry.Point.orNull(0.0, 0.0)).isNull();
        assertThat(RouteGeometry.Point.orNull(37.0, 127.0)).isNull();
        assertThat(RouteGeometry.Point.orNull(Double.NaN, 37.0)).isNull();
    }

    @Test
    void 표시영역은_도로_굴곡과_정류장_좌표를_모두_포함한다() {
        var shape = new RouteGeometry.Shape(UUID.randomUUID(), Instant.EPOCH, List.of(
            new RouteGeometry.Point(127.0, 37.0), new RouteGeometry.Point(127.1, 37.3),
            new RouteGeometry.Point(127.2, 37.0)));
        var map = new RouteGeometry("204000057", 7, "3330", "A", "B", null,
            List.of(new RouteGeometry.Stop("206000637", 1, "A", "UP", true, new RouteGeometry.Point(126.9,37))), shape);
        assertThat(map.boundary()).isEqualTo(new RouteGeometry.Boundary(126.9, 37.3, 127.2, 37.0));
        assertThat(map.points()).hasSize(3);
    }
}
