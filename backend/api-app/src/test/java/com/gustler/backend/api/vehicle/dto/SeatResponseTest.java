package com.gustler.backend.api.vehicle.dto;

import com.gustler.backend.api.vehicle.domain.VehicleSeat;
import com.gustler.backend.api.vehicle.domain.VehicleSeat.Unknown;
import com.gustler.backend.api.vehicle.dto.SeatResponse.Exact;
import com.gustler.backend.api.vehicle.dto.SeatResponse.Kind;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SeatResponseTest {
    @Test
    void 정확한_좌석_응답은_EXACT_종류를_스스로_결정한다() {
        // when
        Exact actual = (Exact) SeatResponse.from(
            new VehicleSeat.Exact(23)
        );

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(actual.kind()).isEqualTo(Kind.EXACT);
            softly.assertThat(actual.remaining()).isEqualTo(23);
        }
    }

    @ParameterizedTest
    @EnumSource(Unknown.class)
    void 미상_좌석_응답은_UNKNOWN과_사유를_함께_반환한다(Unknown reason) {
        // when
        SeatResponse.Unknown actual = (SeatResponse.Unknown) SeatResponse.from(
            reason
        );

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(actual.kind()).isEqualTo(Kind.UNKNOWN);
            softly.assertThat(actual.reason()).isEqualTo(reason);
        }
    }
}
