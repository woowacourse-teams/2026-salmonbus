package com.gustler.backend.collector;

import static com.gustler.backend.collector.StopDirection.DOWN;
import static com.gustler.backend.collector.StopDirection.UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RouteStopsTest {

    private static final int TURN_SEQUENCE_3330 = 43;
    private static final int STOP_COUNT_3330 = 85;
    private static final String STOP_208000069 = "208000069"; // 안양역. 3330 회차 정류소
    private static final String STOP_277103149 = "277103149"; // 경유 지점
    private static final String STOP_205000217 = "205000217"; // 범계역
    private static final String ANY_STOP_NAME = "안양역";

    @Test
    void 회차_순번이_43이면_43번_정류소가_상행이고_44번_정류소가_하행이다() {
        // given
        RouteStops routeStops = fold3330();

        // when
        List<StopDirection> actual = List.of(
            directionAt(routeStops, TURN_SEQUENCE_3330),
            directionAt(routeStops, TURN_SEQUENCE_3330 + 1)
        );

        // then
        assertThat(actual).containsExactly(UP, DOWN);
    }

    @Test
    void 회차_순번이_없으면_모든_정류소가_상행이다() {
        // given
        RouteStops routeStops = RouteStops.from(null, List.of(
            new UpstreamRouteStop(1, STOP_205000217, ANY_STOP_NAME),
            new UpstreamRouteStop(2, STOP_208000069, ANY_STOP_NAME),
            new UpstreamRouteStop(3, STOP_205000217, ANY_STOP_NAME)
        ));

        // when
        List<StopDirection> actual = routeStops.stops().stream().map(RouteStop::direction).toList();

        // then
        assertThat(actual).containsOnly(UP);
    }

    @Test
    void 정류소_순번에_GBIS_stationSeq를_그대로_넣는다() {
        // given
        RouteStops routeStops = RouteStops.from(6, List.of(
            new UpstreamRouteStop(6, STOP_205000217, ANY_STOP_NAME)
        ));

        // when
        final int actual = routeStops.stops().getFirst().stopOrder();

        // then
        assertThat(actual).isEqualTo(6);
    }

    @Test
    void 같은_정류소를_두_번_지나도_순번으로_구분된다() {
        // given
        RouteStops routeStops = RouteStops.from(2, List.of(
            new UpstreamRouteStop(2, STOP_208000069, ANY_STOP_NAME),
            new UpstreamRouteStop(3, STOP_208000069, ANY_STOP_NAME)
        ));

        // when
        List<Integer> actual = routeStops.stops().stream().map(RouteStop::stopOrder).toList();

        // then
        assertThat(actual).containsExactly(2, 3);
    }

    @Test
    void 정류소_목록을_접으면_정류소마다_승차_가능_여부가_정해진다() {
        // given
        RouteStops routeStops = RouteStops.from(3, List.of(
            new UpstreamRouteStop(1, STOP_205000217, ANY_STOP_NAME),
            new UpstreamRouteStop(2, STOP_277103149, ANY_STOP_NAME),
            new UpstreamRouteStop(3, STOP_208000069, ANY_STOP_NAME)
        ));

        // when
        List<Boolean> actual = routeStops.stops().stream().map(RouteStop::boardingAllowed).toList();

        // then
        assertThat(actual).containsExactly(true, false, true);
    }

    @Test
    void 한_판본에_같은_정류소_순번은_한_번만_나온다() {
        // when
        Throwable actual = catchThrowable(() -> RouteStops.from(1, List.of(
            new UpstreamRouteStop(1, STOP_205000217, ANY_STOP_NAME),
            new UpstreamRouteStop(1, STOP_208000069, ANY_STOP_NAME))));

        // then
        assertThat(actual).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 999})
    void 회차_순번은_경유하는_정류소_중_하나의_순번이다(
        final int turnSequence
    ) {
        // when
        Throwable actual = catchThrowable(() -> RouteStops.from(turnSequence, eightyFiveUpstreamStops()));

        // then
        assertThat(actual).isInstanceOf(IllegalArgumentException.class);
    }

    private RouteStops fold3330() {
        return RouteStops.from(TURN_SEQUENCE_3330, eightyFiveUpstreamStops());
    }

    private List<UpstreamRouteStop> eightyFiveUpstreamStops() {
        return IntStream.rangeClosed(1, STOP_COUNT_3330)
            .mapToObj(stopOrder -> new UpstreamRouteStop(stopOrder, STOP_205000217, ANY_STOP_NAME))
            .toList();
    }

    private StopDirection directionAt(
        RouteStops routeStops,
        final int stopOrder
    ) {
        return routeStops.stops().stream()
            .filter(stop -> stop.stopOrder() == stopOrder)
            .findFirst()
            .orElseThrow()
            .direction();
    }
}
