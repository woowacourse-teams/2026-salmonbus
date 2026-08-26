package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class VehicleStopTargetTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-25T08:30:00Z");

    @Test
    void 차량이_3번_정류장에서_5정류장_앞을_예보하면_대상은_8번_정류장이다() {
        // given
        final VehicleStopTarget target = new VehicleStopTarget(observed(3, 12), Horizon.of(5));

        // when
        final int actual = target.targetStopOrder();

        // then
        assertThat(actual).isEqualTo(8);
    }

    @Test
    void 잔여석을_아는_차량만_예보한다() {
        // given
        final ObservedVehicle seatsUnknown = observed(3, null);

        // when & then
        assertThatThrownBy(() -> new VehicleStopTarget(seatsUnknown, Horizon.of(5)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잔여석_12석으로_관측한_차량은_예보_입력도_12석이다() {
        // given
        final VehicleStopTarget target = new VehicleStopTarget(observed(3, 12), Horizon.of(5));

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
