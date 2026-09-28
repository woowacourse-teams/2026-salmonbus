package com.gustler.backend.observations.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.SchemaCheck;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StoredValueContractTest {

    @Test
    void 잔여석_미제공_사유는_관측_표의_CHECK와_같다() {
        // when
        var allowed = SchemaCheck.allowedValues("V4__observation.sql", "seat_unknown_reason");

        // then
        assertThat(Arrays.stream(SeatUnknownReason.values()).map(Enum::name))
            .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    void 수집_결과는_배치_표의_CHECK와_같다() {
        // when
        var allowed = SchemaCheck.allowedValues("V6__call_reservation.sql", "outcome");

        // then
        assertThat(Arrays.stream(ObservationBatchOutcome.values()).map(Enum::name))
            .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    void 수집_실패_사유는_배치_표의_CHECK와_같다() {
        // when
        var allowed = SchemaCheck.allowedValues("V6__call_reservation.sql", "failure_code");

        // then
        assertThat(Arrays.stream(ObservationBatchFailureCode.values()).map(Enum::name))
            .containsExactlyInAnyOrderElementsOf(allowed);
    }
}
