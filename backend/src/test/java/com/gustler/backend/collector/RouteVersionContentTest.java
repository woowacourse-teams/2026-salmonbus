package com.gustler.backend.collector;

import static com.gustler.backend.collector.RouteVersionDecision.KEEP_CURRENT_VERSION;
import static com.gustler.backend.collector.RouteVersionDecision.OPEN_NEW_VERSION;
import static com.gustler.backend.collector.RouteVersionDecision.REVISE_TIMETABLE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteVersionContentTest {

    private static final String STOP_205000217 = "205000217";
    private static final String STOP_208000069 = "208000069";
    private static final RouteTimetable TIMETABLE_1650 =
        new RouteTimetable("05:00", "22:35", "05:00", "23:55");
    private static final RouteTimetable TIMETABLE_1650_LAST_BUS_MOVED =
        new RouteTimetable("05:00", "22:50", "05:00", "23:55");

    @Test
    void 직전_판본과_정류소가_다르면_새로_끊는다() {
        // given
        RouteVersionContent stored = RouteVersionContent.of(twoStops(), TIMETABLE_1650);

        // when
        RouteVersionDecision actual = stored.decideFor(RouteVersionContent.of(threeStops(), TIMETABLE_1650));

        // then
        assertThat(actual).isEqualTo(OPEN_NEW_VERSION);
    }

    @Test
    void 시간표만_바뀌면_같은_판본의_첫차와_막차를_고친다() {
        // given
        RouteVersionContent stored = RouteVersionContent.of(twoStops(), TIMETABLE_1650);

        // when
        RouteVersionDecision actual =
            stored.decideFor(RouteVersionContent.of(twoStops(), TIMETABLE_1650_LAST_BUS_MOVED));

        // then
        assertThat(actual).isEqualTo(REVISE_TIMETABLE);
    }

    @Test
    void 직전_판본과_정류소도_시간표도_같으면_그_판본을_그대로_쓴다() {
        // given
        RouteVersionContent stored = RouteVersionContent.of(twoStops(), TIMETABLE_1650);

        // when
        RouteVersionDecision actual = stored.decideFor(RouteVersionContent.of(twoStops(), TIMETABLE_1650));

        // then
        assertThat(actual).isEqualTo(KEEP_CURRENT_VERSION);
    }

    private RouteStops twoStops() {
        return RouteStops.from(1, List.of(
            new UpstreamRouteStop(1, STOP_205000217, "범계역"),
            new UpstreamRouteStop(2, STOP_208000069, "안양역")
        ));
    }

    private RouteStops threeStops() {
        return RouteStops.from(1, List.of(
            new UpstreamRouteStop(1, STOP_205000217, "범계역"),
            new UpstreamRouteStop(2, STOP_208000069, "안양역"),
            new UpstreamRouteStop(3, STOP_205000217, "범계역")
        ));
    }
}
