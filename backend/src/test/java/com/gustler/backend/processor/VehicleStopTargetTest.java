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

    @Test
    void 차량이_3번_정류장에서_5정류장_앞을_예보하면_대상은_8번_정류장이다() {
        // given
        final VehicleStopTarget target = new VehicleStopTarget(observed(3, 12), new ForecastDistance(5));

        // when
        final int actual = target.stopOrderToForecast();

        // then
        assertThat(actual).isEqualTo(8);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1})
    void 잔여석을_아는_차량만_예보한다(
        final Integer unknownSeats
    ) {
        // given
        final ObservedVehicle seatsUnknown = observed(3, unknownSeats);

        // when & then
        assertThatThrownBy(() -> new VehicleStopTarget(seatsUnknown, new ForecastDistance(5)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잔여석_12석으로_관측한_차량은_예보_입력도_12석이다() {
        // given
        final VehicleStopTarget target = new VehicleStopTarget(observed(3, 12), new ForecastDistance(5));

        // when
        final int actual = target.remainingSeats();

        // then
        assertThat(actual).isEqualTo(12);
    }

    private ObservedVehicle observed(
        final int stopOrder,
        final Integer remainingSeats
    ) {
        return new ObservedVehicle("204000206", stopOrder, OBSERVED_AT, remainingSeats);
    }
}
