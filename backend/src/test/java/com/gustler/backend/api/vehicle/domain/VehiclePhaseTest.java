package com.gustler.backend.api.vehicle.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VehiclePhaseTest {

    @ParameterizedTest
    @CsvSource({
        "0, IN_TRANSIT",
        "1, ARRIVING",
        "2, DEPARTED"
    })
    void 상류_운행_상태를_API_차량_단계로_변환한다(
        int runningState,
        VehiclePhase expected
    ) {
        assertThat(VehiclePhase.fromRunningState(runningState)).isEqualTo(expected);
    }
}
