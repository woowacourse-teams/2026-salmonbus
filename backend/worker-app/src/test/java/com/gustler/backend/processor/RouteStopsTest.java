package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteStopsTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-25T08:30:00Z");
    /** 혼잡도를 안 준 관측. 이 테스트가 보는 것과 무관하다. */
    private static final Integer CROWD_LEVEL_UNKNOWN = null;

    private static final long ROUTE_VERSION_3330 = 1L;

    private static final String UPSTREAM_ROUTE_3330 = "204000057";
    private static final int SEATS_LEFT = 12;

    @Test
    void 승차할_수_있는_정류장만_예보_대상이_된다() {
        // given
        RouteStops stops = new RouteStops(ROUTE_VERSION_3330, UPSTREAM_ROUTE_3330, List.of(
            boardingStop(1),
            passingStop(2),
            boardingStop(3)));

        // when
        List<VehicleStopTarget> actual = stops.targetsAheadOf(observed(0, SEATS_LEFT));

        // then
        assertThat(actual).extracting(VehicleStopTarget::stopOrder).containsExactly(1, 3);
    }

    @Test
    void 차량_앞_12개_정류장까지만_예보_대상이_된다() {
        // given
        RouteStops stops = boardingStopsUpTo(30);

        // when
        List<VehicleStopTarget> actual = stops.targetsAheadOf(observed(10, SEATS_LEFT));

        // then
        assertThat(actual).extracting(VehicleStopTarget::stopOrder).containsExactly(
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22);
    }

    @Test
    void 여정에_남은_정류장이_12개보다_적으면_남은_만큼만_대상이_된다() {
        // given
        RouteStops stops = boardingStopsUpTo(5);

        // when
        List<VehicleStopTarget> actual = stops.targetsAheadOf(observed(2, SEATS_LEFT));

        // then
        assertThat(actual).extracting(VehicleStopTarget::stopOrder).containsExactly(3, 4, 5);
    }

    @Test
    void 종점을_지나_순번_1로_돌아가는_자리는_대상에서_뺀다() {
        // given
        RouteStops stops = boardingStopsUpTo(5);

        // when
        List<VehicleStopTarget> actual = stops.targetsAheadOf(observed(4, SEATS_LEFT));

        // then
        assertThat(actual).extracting(VehicleStopTarget::stopOrder).containsExactly(5);
    }

    @Test
    void 정류장_3번을_지난_차량이_5정류장_앞을_보면_대상은_8번_정류장이다() {
        // given
        RouteStops stops = boardingStopsUpTo(20);

        // when
        List<VehicleStopTarget> actual = stops.targetsAheadOf(observed(3, SEATS_LEFT));

        // then
        assertThat(actual.get(4).stopOrder()).isEqualTo(8);
    }

    @Test
    void 경유_정류장도_지평을_한_칸으로_센다() {
        // given
        RouteStops stops = new RouteStops(ROUTE_VERSION_3330, UPSTREAM_ROUTE_3330, List.of(
            boardingStop(1),
            boardingStop(2),
            passingStop(3),
            boardingStop(4)));

        // when
        List<VehicleStopTarget> actual = stops.targetsAheadOf(observed(2, SEATS_LEFT));

        // then
        assertThat(actual.getFirst().distance().stopCount()).isEqualTo(2);
    }

    @Test
    void 승차할_수_있는_정류장이_앞에_없으면_대상이_하나도_안_나온다() {
        // given
        RouteStops stops = new RouteStops(ROUTE_VERSION_3330, UPSTREAM_ROUTE_3330, List.of(
            boardingStop(1),
            passingStop(2),
            passingStop(3)));

        // when
        List<VehicleStopTarget> actual = stops.targetsAheadOf(observed(1, SEATS_LEFT));

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 잔여석을_아는_차량만_예보_대상을_받는다() {
        // given
        RouteStops stops = boardingStopsUpTo(20);

        // when
        List<VehicleStopTarget> actual = stops.targetsAheadOf(observed(3, null));

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 첫_정류장에_도착_중인_차량은_1번_정류장부터_대상이_된다() {
        // given
        RouteStops stops = boardingStopsUpTo(20);

        // when
        List<VehicleStopTarget> actual = stops.targetsAheadOf(observed(0, SEATS_LEFT));

        // then
        assertThat(actual.getFirst().stopOrder()).isEqualTo(1);
    }

    @Test
    void 정류장은_전부_같은_노선_판본의_것이다() {
        // given
        final long otherRouteVersion = 2L;
        List<RouteStop> mixed = List.of(
            boardingStop(1),
            new RouteStop(otherRouteVersion, 2, "20402", true));

        // when & then
        assertThatThrownBy(() -> new RouteStops(ROUTE_VERSION_3330, UPSTREAM_ROUTE_3330, mixed))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 판본의_마지막_순번은_정류장_중_가장_큰_순번이다() {
        // given 경유 지점이 마지막 순번을 차지해도 그것까지 센다
        RouteStops stops = new RouteStops(ROUTE_VERSION_3330, UPSTREAM_ROUTE_3330, List.of(boardingStop(1), passingStop(60)));

        // when
        final int actual = stops.largestStopOrder();

        // then 정류장 위치를 0에서 1 사이로 옮길 때 나누는 수다
        assertThat(actual).isEqualTo(60);
    }

    private RouteStops boardingStopsUpTo(
        final int lastStopOrder
    ) {
        List<RouteStop> stops = new ArrayList<>();
        for (int stopOrder = 1; stopOrder <= lastStopOrder; stopOrder++) {
            stops.add(boardingStop(stopOrder));
        }
        return new RouteStops(ROUTE_VERSION_3330, UPSTREAM_ROUTE_3330, stops);
    }

    private RouteStop boardingStop(
        final int stopOrder
    ) {
        return new RouteStop(ROUTE_VERSION_3330, stopOrder, "20400" + stopOrder, true);
    }

    private RouteStop passingStop(
        final int stopOrder
    ) {
        return new RouteStop(ROUTE_VERSION_3330, stopOrder, "27700" + stopOrder, false);
    }

    private ObservedVehicle observed(
        final int passedStopOrder,
        Integer remainingSeats
    ) {
        return new ObservedVehicle(
            "204000206", ROUTE_VERSION_3330, passedStopOrder, OBSERVED_AT, remainingSeats, CROWD_LEVEL_UNKNOWN);
    }
}
