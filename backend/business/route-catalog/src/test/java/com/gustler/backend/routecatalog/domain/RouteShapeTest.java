package com.gustler.backend.routecatalog.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteShapeTest {
    @Test
    void 빈_형상이나_같은_점만_있는_형상은_정상_경로로_저장하지_않는다() {
        var point = new RouteShape.Point(127,37);
        assertThatThrownBy(() -> new RouteShape(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RouteShape(List.of(point,point))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 미제공0_비유한값_축오류는_형상으로_받지_않는다() {
        assertThatThrownBy(() -> new RouteShape.Point(0,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RouteShape.Point(37,127)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RouteShape.Point(Double.POSITIVE_INFINITY,37)).isInstanceOf(IllegalArgumentException.class);
    }
}
