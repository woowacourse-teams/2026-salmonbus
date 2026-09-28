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
        assertThat(VehicleSeat.from(-1)).isEqualTo(VehicleSeat.Unknown.REPORTED_UNKNOWN);
        assertThat(VehicleSeat.from(null)).isEqualTo(VehicleSeat.Unknown.NOT_REPORTED);
    }

    @Test
    void 정규화된_음수와_원래_누락된_좌석의_사유를_구분한다() {
        assertThat(VehicleSeat.from(null, "REPORTED_UNKNOWN", true)).isEqualTo(VehicleSeat.Unknown.REPORTED_UNKNOWN);
        assertThat(VehicleSeat.from(null, "NOT_REPORTED", true)).isEqualTo(VehicleSeat.Unknown.NOT_REPORTED);
    }

    @Test
    void 원래_좌석이_없으면_품질_보류보다_원래_사유를_유지한다() {
        assertThat(VehicleSeat.from(null, "REPORTED_UNKNOWN", false)).isEqualTo(VehicleSeat.Unknown.REPORTED_UNKNOWN);
        assertThat(VehicleSeat.from(null, "NOT_REPORTED", false)).isEqualTo(VehicleSeat.Unknown.NOT_REPORTED);
        assertThat(VehicleSeat.from(-1, null, false)).isEqualTo(VehicleSeat.Unknown.REPORTED_UNKNOWN);
    }

    @Test
    void 숫자로_보고된_좌석을_품질_때문에_숨기면_품질_보류_사유를_반환한다() {
        assertThat(VehicleSeat.from(40, null, false)).isEqualTo(VehicleSeat.Unknown.QUALITY_WITHHELD);
        assertThat(VehicleSeat.from(82, null, false)).isEqualTo(VehicleSeat.Unknown.QUALITY_WITHHELD);
        assertThat(VehicleSeat.from(0, null, true)).isEqualTo(new VehicleSeat.Exact(0));
    }
}
