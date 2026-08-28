package com.gustler.backend.api.vehicle.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VehicleSeatTest {

    @Test
    void 잔여_좌석이_0이면_정확히_0석으로_해석한다() {
        assertThat(VehicleSeat.from(0)).isEqualTo(new VehicleSeat.Exact(0));
    }

    @Test
    void 잔여_좌석이_음수이거나_누락되면_알_수_없음으로_해석한다() {
        assertThat(VehicleSeat.from(-1)).isEqualTo(VehicleSeat.Unknown.INSTANCE);
        assertThat(VehicleSeat.from(null)).isEqualTo(VehicleSeat.Unknown.INSTANCE);
    }
}
