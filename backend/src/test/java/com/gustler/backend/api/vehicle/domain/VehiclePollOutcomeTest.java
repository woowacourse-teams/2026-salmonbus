package com.gustler.backend.api.vehicle.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class VehiclePollOutcomeTest {

    @ParameterizedTest
    @CsvSource({
        "SUCCESS_ROWS, SUCCESS_ROWS",
        "SUCCESS_EMPTY, SUCCESS_EMPTY"
    })
    void 정상_DB_outcome을_API가_사용하는_타입으로_변환한다(
        String databaseValue,
        VehiclePollOutcome expected
    ) {
        assertThat(VehiclePollOutcome.fromDatabaseValue(databaseValue)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"TRANSPORT_FAILURE", "IN_PROGRESS", "UNEXPECTED"})
    void 정상이_아닌_DB_outcome은_UNKNOWN으로_해석한다(String databaseValue) {
        assertThat(VehiclePollOutcome.fromDatabaseValue(databaseValue))
            .isEqualTo(VehiclePollOutcome.UNKNOWN);
    }
}
