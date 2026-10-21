package com.gustler.backend.routecatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape.Point;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteShapeTest {
    @Test
    void 빈_형상이나_같은_점만_있는_형상은_정상_경로로_저장하지_않는다() {
        // when
        Point point = new Point(127, 37);

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> new RouteShape(List.of()));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure2 = catchThrowable(() -> new RouteShape(List.of(point, point)));

        // then
        assertThat(phaseFailure2)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 미제공0_비유한값_축오류는_형상으로_받지_않는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> new Point(0, 0));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure1 = catchThrowable(() -> new Point(37, 127));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure2 = catchThrowable(() -> new Point(Double.POSITIVE_INFINITY, 37));

        // then
        assertThat(phaseFailure2)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }
}
