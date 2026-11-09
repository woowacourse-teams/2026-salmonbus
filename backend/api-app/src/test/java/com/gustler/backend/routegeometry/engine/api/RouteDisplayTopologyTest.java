package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RoutePaths;

import org.junit.jupiter.api.Test;

import java.util.List;

class RouteDisplayTopologyTest {
    private RoutePaths paths(double x) {
        return new RoutePaths(
                new RoutePaths.Path(List.of(new Point(127, 37), new Point(127.002, 37.002))),
                new RoutePaths.Path(
                        List.of(new Point(127 + x, 37.002), new Point(127.002 + x, 37))));
    }

    @Test
    void 교차개수가_같아도_위치가_바뀌면_새교차로_검출한다() {
        var before = paths(0);
        var after = paths(0.0002);
        assertThat(RouteDisplayTopology.crossings(before).points()).hasSize(1);
        assertThat(RouteDisplayTopology.crossings(after).points()).hasSize(1);
        assertThat(RouteDisplayTopology.noNewCrossings(before, after)).isFalse();
    }

    @Test
    void 기존교차를_통과하는_원선분의_끝만_줄인_경로는_허용한다() {
        var before = paths(0);
        var after =
                new RoutePaths(
                        new RoutePaths.Path(
                                List.of(new Point(127.0001, 37.0001), new Point(127.002, 37.002))),
                        before.down());
        assertThat(RouteDisplayTopology.noNewCrossings(before, after)).isTrue();
    }
}
