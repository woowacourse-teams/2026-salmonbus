package com.gustler.backend.api.vehicle.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VehicleSeatTest {
    @Test
    void 잔여_좌석이_0이면_정확히_0석으로_해석한다() {
        // when
        VehicleSeat seat1 = VehicleSeat.from(0);

        // then
        assertThat(seat1).isEqualTo(new VehicleSeat.Exact(0));
    }

    @Test
    void 잔여_좌석이_음수이거나_누락되면_알_수_없음으로_해석한다() {
        // when
        VehicleSeat seat1 = VehicleSeat.from(-1);

        // then
        assertThat(seat1).isEqualTo(VehicleSeat.Unknown.REPORTED_UNKNOWN);

        // when
        VehicleSeat seat2 = VehicleSeat.from(null);

        // then
        assertThat(seat2).isEqualTo(VehicleSeat.Unknown.NOT_REPORTED);
    }

    @Test
    void 정규화된_음수와_원래_누락된_좌석의_사유를_구분한다() {
        // when
        VehicleSeat seat1 = VehicleSeat.from(null, "REPORTED_UNKNOWN");

        // then
        assertThat(seat1).isEqualTo(VehicleSeat.Unknown.REPORTED_UNKNOWN);

        // when
        VehicleSeat seat2 = VehicleSeat.from(null, "NOT_REPORTED");

        // then
        assertThat(seat2).isEqualTo(VehicleSeat.Unknown.NOT_REPORTED);
    }
}
