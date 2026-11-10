package com.gustler.backend.forecasting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.model.SeatUnknownReason;
import com.gustler.backend.support.SchemaCheck;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StoredValueContractTest {
    @Test
    void 관측_표에_저장된_잔여석_미제공_사유를_모두_읽을_수_있다() {
        // when
        Set<String> stored = SchemaCheck.allowedValues("V4__observation.sql", "seat_unknown_reason");

        // then
        assertThat(Arrays.stream(SeatUnknownReason.values())
            .map(Enum::name)).containsAll(stored);
    }

    @Test
    void 평가_상태는_평가_표의_CHECK와_같다() {
        // when
        Set<String> allowed = SchemaCheck.allowedValues("V70__retire_seat_distribution_evaluation.sql", "scoring_state");

        // then
        assertThat(Arrays.stream(ScoringState.values())
            .map(Enum::name)).containsExactlyInAnyOrderElementsOf(allowed);
    }

}
