package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class VehicleStopTargetTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-25T08:30:00Z");
    private static final long ROUTE_VERSION_3330 = 1L;

    @Test
    void 정류장_44번까지_지나온_차량이_49번_정류장을_예보하면_거리는_5정류장이다() {
        // given
        VehicleStopTarget target = new VehicleStopTarget(observed(44, 12), routeStop(49));

        // when
        ForecastDistance actual = target.distance();

        // then
        assertThat(actual.stopCount()).isEqualTo(5);
    }

    @Test
    void 예보_대상은_지나온_정류장의_다음_정류장부터다() {
        // given
        RouteStop passedStop = routeStop(40);

        // when & then
        assertThatThrownBy(() -> new VehicleStopTarget(observed(44, 12), passedStop))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 예보_대상은_12정류장_앞까지다() {
        // given
        RouteStop farStop = routeStop(57);

        // when & then
        assertThatThrownBy(() -> new VehicleStopTarget(observed(44, 12), farStop))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1})
    void 잔여석을_아는_차량만_예보한다(
        Integer unknownSeats
    ) {
        // given
        ObservedVehicle seatsUnknown = observed(44, unknownSeats);

        // when & then
        assertThatThrownBy(() -> new VehicleStopTarget(seatsUnknown, routeStop(49)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잔여석_12석으로_관측한_차량은_예보_입력도_12석이다() {
        // given
        VehicleStopTarget target = new VehicleStopTarget(observed(44, 12), routeStop(49));

        // when
        final int actual = target.remainingSeats();

        // then
        assertThat(actual).isEqualTo(12);
    }

    private ObservedVehicle observed(
        final int passedStopOrder,
        Integer remainingSeats
    ) {
        return new ObservedVehicle("204000206", passedStopOrder, OBSERVED_AT, remainingSeats);
    }

    private RouteStop routeStop(
        final int stopOrder
    ) {
        return new RouteStop(ROUTE_VERSION_3330, stopOrder, "20400" + stopOrder);
    }
}
