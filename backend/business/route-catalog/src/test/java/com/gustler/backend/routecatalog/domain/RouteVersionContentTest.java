package com.gustler.backend.routecatalog.domain;

import static com.gustler.backend.routecatalog.domain.RouteVersionDecision.KEEP_CURRENT_VERSION;
import static com.gustler.backend.routecatalog.domain.RouteVersionDecision.OPEN_NEW_VERSION;
import static com.gustler.backend.routecatalog.domain.RouteVersionDecision.REVISE_TIMETABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteVersionContentTest {
    private static final String STOP_205000217 = "205000217";
    private static final String STOP_208000069 = "208000069";
    private static final RouteTimetable TIMETABLE_1650 =
        new RouteTimetable("05:00", "22:35", "05:00", "23:55", RouteHeadways.none());
    private static final RouteTimetable TIMETABLE_1650_LAST_BUS_MOVED =
        new RouteTimetable("05:00", "22:50", "05:00", "23:55", RouteHeadways.none());
    private static final RouteTimetable TIMETABLE_1650_WITH_HEADWAYS =
        new RouteTimetable("05:00", "22:35", "05:00", "23:55", new RouteHeadways(
            new HeadwayRange(10, 20), new HeadwayRange(12, 25), new HeadwayRange(15, 30), null));

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
    void 배차간격만_바뀌어도_노선_버전을_새로_끊지_않고_같은_버전의_시간표를_고친다() {
        // given
        RouteVersionContent stored = RouteVersionContent.of(twoStops(), TIMETABLE_1650);

        // when
        RouteVersionDecision actual =
            stored.decideFor(RouteVersionContent.of(twoStops(), TIMETABLE_1650_WITH_HEADWAYS));

        // then
        assertThat(actual).isEqualTo(REVISE_TIMETABLE);
    }

    @Test
    void 직전_노선_버전과_정류소도_배차간격도_같으면_그_버전을_그대로_쓴다() {
        // given
        RouteVersionContent stored = RouteVersionContent.of(twoStops(), TIMETABLE_1650_WITH_HEADWAYS);

        // when
        RouteVersionDecision actual =
            stored.decideFor(RouteVersionContent.of(twoStops(), TIMETABLE_1650_WITH_HEADWAYS));

        // then
        assertThat(actual).isEqualTo(KEEP_CURRENT_VERSION);
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

    @Test
    void 읽지_못한_배차간격이_남은_시간표로는_판정하지_않는다() {
        // given
        RouteTimetable unresolved = new RouteTimetable("05:00", "22:35", "05:00", "23:55",
            new RouteHeadways(null, null, null, null, Set.of(ServiceDay.WEEKDAY)));

        // when
        Throwable caughtFailure = catchThrowable(() -> RouteVersionContent.of(twoStops(), unresolved));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
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
