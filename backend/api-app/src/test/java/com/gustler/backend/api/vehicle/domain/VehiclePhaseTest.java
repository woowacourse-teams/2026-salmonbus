package com.gustler.backend.api.vehicle.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class VehiclePhaseTest {
    @ParameterizedTest
    @CsvSource({
        "0, IN_TRANSIT",
        "1, ARRIVING",
        "2, DEPARTED"
    })
    void 상류_운행_상태를_API_차량_단계로_변환한다(
        final int runningState,
        VehiclePhase expected
    ) {
        // when
        Optional<VehiclePhase> phase = VehiclePhase.fromRunningState(runningState);

        // then
        assertThat(phase).contains(expected);
    }

    @Test
    void 운행_상태가_비어_있으면_해석하지_않는다() {
        // when
        Optional<VehiclePhase> phase = VehiclePhase.fromRunningState(null);

        // then
        assertThat(phase).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 3, 9})
    void 문서_밖_운행_상태는_해석하지_않는다(final int runningState) {
        // when
        Optional<VehiclePhase> phase = VehiclePhase.fromRunningState(runningState);

        // then
        assertThat(phase).isEmpty();
    }
}
