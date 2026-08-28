package com.gustler.backend.api.vehicle.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.vehicle.domain.VehicleSeat;
import org.junit.jupiter.api.Test;

class SeatResponseTest {

    @Test
    void 정확한_좌석_응답은_EXACT_종류를_스스로_결정한다() {
        SeatResponse.Exact actual = (SeatResponse.Exact) SeatResponse.from(
            new VehicleSeat.Exact(23)
        );

        assertThat(actual.kind()).isEqualTo(SeatResponse.Kind.EXACT);
        assertThat(actual.remaining()).isEqualTo(23);
    }

    @Test
    void 미상_좌석_응답은_UNKNOWN_종류를_스스로_결정한다() {
        SeatResponse.Unknown actual = (SeatResponse.Unknown) SeatResponse.from(
            VehicleSeat.Unknown.INSTANCE
        );

        assertThat(actual.kind()).isEqualTo(SeatResponse.Kind.UNKNOWN);
    }
}
