package com.gustler.backend.api.vehicle.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.vehicle.domain.VehicleSeat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SeatResponseTest {

    @Test
    void 정확한_좌석_응답은_EXACT_종류를_스스로_결정한다() {
        SeatResponse.Exact actual = (SeatResponse.Exact) SeatResponse.from(
            new VehicleSeat.Exact(23)
        );

        assertThat(actual.kind()).isEqualTo(SeatResponse.Kind.EXACT);
        assertThat(actual.remaining()).isEqualTo(23);
    }

    @ParameterizedTest
    @EnumSource(VehicleSeat.Unknown.class)
    void 미상_좌석_응답은_UNKNOWN과_사유를_함께_반환한다(VehicleSeat.Unknown reason) {
        SeatResponse.Unknown actual = (SeatResponse.Unknown) SeatResponse.from(
            reason
        );

        assertThat(actual.kind()).isEqualTo(SeatResponse.Kind.UNKNOWN);
        assertThat(actual.reason()).isEqualTo(reason);
    }
}
