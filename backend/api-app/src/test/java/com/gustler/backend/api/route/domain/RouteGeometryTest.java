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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RouteGeometryTest {
    @ParameterizedTest
    @CsvSource(value = {
        "null, 37.0",
        "0.0, 0.0",
        "37.0, 127.0",
        "NaN, 37.0"
    }, nullValues = "null")
    void 미제공이거나_표시할_수_없는_좌표를_지도_마커로_내보내지_않는다(Double x, Double y) {
        // when
        Point point = Point.orNull(x, y);

        // then
        assertThat(point).isNull();
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
