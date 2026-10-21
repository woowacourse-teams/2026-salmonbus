package com.gustler.backend.api.vehicle.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.vehicle.domain.VehicleSeat.Exact;
import com.gustler.backend.api.vehicle.domain.VehicleSeat.Unknown;
import org.junit.jupiter.api.Test;

class VehicleSeatTest {
    @Test
    void 잔여_좌석이_0이면_정확히_0석으로_해석한다() {
        // when
        VehicleSeat seat1 = VehicleSeat.from(0);

        // then
        assertThat(seat1).isEqualTo(new Exact(0));
    }

    @Test
    void 잔여_좌석이_음수이거나_누락되면_알_수_없음으로_해석한다() {
        // when
        VehicleSeat seat1 = VehicleSeat.from(-1);

        // then
        assertThat(seat1).isEqualTo(Unknown.REPORTED_UNKNOWN);

        // when
        VehicleSeat seat2 = VehicleSeat.from(null);

        // then
        assertThat(seat2).isEqualTo(Unknown.NOT_REPORTED);
    }

    @Test
    void 정규화된_음수와_원래_누락된_좌석의_사유를_구분한다() {
        // when
        VehicleSeat seat1 = VehicleSeat.from(null, "REPORTED_UNKNOWN", true);

        // then
        assertThat(seat1).isEqualTo(Unknown.REPORTED_UNKNOWN);

        // when
        VehicleSeat seat2 = VehicleSeat.from(null, "NOT_REPORTED", true);

        // then
        assertThat(seat2).isEqualTo(Unknown.NOT_REPORTED);
    }

    @Test
    void 원래_좌석이_없으면_품질_보류보다_원래_사유를_유지한다() {
        // when
        VehicleSeat seat1 = VehicleSeat.from(null, "REPORTED_UNKNOWN", false);

        // then
        assertThat(seat1).isEqualTo(Unknown.REPORTED_UNKNOWN);

        // when
        VehicleSeat seat2 = VehicleSeat.from(null, "NOT_REPORTED", false);

        // then
        assertThat(seat2).isEqualTo(Unknown.NOT_REPORTED);

        // when
        VehicleSeat seat3 = VehicleSeat.from(-1, null, false);

        // then
        assertThat(seat3).isEqualTo(Unknown.REPORTED_UNKNOWN);
    }

    @Test
    void 숫자로_보고된_좌석을_품질_때문에_숨기면_품질_보류_사유를_반환한다() {
        // when
        VehicleSeat seat1 = VehicleSeat.from(40, null, false);

        // then
        assertThat(seat1).isEqualTo(Unknown.QUALITY_WITHHELD);

        // when
        VehicleSeat seat2 = VehicleSeat.from(82, null, false);

        // then
        assertThat(seat2).isEqualTo(Unknown.QUALITY_WITHHELD);

        // when
        VehicleSeat seat3 = VehicleSeat.from(0, null, true);

        // then
        assertThat(seat3).isEqualTo(new Exact(0));
    }
}
