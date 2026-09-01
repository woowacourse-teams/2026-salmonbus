package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 정류장 위치 층이 순번을 노선 안의 자리로 옮기는 것을 본다.
 *
 * <p>순번을 그대로 쓰지 않는 이유가 여기 나온다. 판본마다 정류장 수가 달라서 같은 순번이라도
 * 노선에서 서 있는 자리가 다르다.
 */
class StopPositionOnRouteTest {

    private static final long ROUTE_VERSION_3330 = 1L;

    @Test
    void 정류장_60개짜리_판본의_30번_정류장은_0점5다() {
        // when
        final double actual = StopPositionOnRoute.of(30, stopsUpTo(60));

        // then
        assertThat(actual).isEqualTo(0.5);
    }

    @Test
    void 마지막_정류장은_1이다() {
        // when
        final double actual = StopPositionOnRoute.of(60, stopsUpTo(60));

        // then
        assertThat(actual).isEqualTo(1.0);
    }

    @Test
    void 순번이_같아도_정류장_수가_다르면_다른_자리다() {
        // when
        final double actual = StopPositionOnRoute.of(30, stopsUpTo(40));

        // then 60개짜리 판본에서는 같은 30번이 0.5 였다
        assertThat(actual).isEqualTo(0.75);
    }

    private static RouteStops stopsUpTo(
        final int lastStopOrder
    ) {
        List<RouteStop> stops = new ArrayList<>();
        for (int stopOrder = 1; stopOrder <= lastStopOrder; stopOrder++) {
            stops.add(new RouteStop(ROUTE_VERSION_3330, stopOrder, "2040000%d".formatted(stopOrder), true));
        }
        return new RouteStops(ROUTE_VERSION_3330, stops);
    }
}
