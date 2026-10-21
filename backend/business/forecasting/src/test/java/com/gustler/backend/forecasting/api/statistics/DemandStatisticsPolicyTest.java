package com.gustler.backend.forecasting.api.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DemandStatisticsPolicyTest {
    @Test
    void 통계_주기가_양수면_만든다() {
        // when
        DemandStatisticsPolicy actual = new DemandStatisticsPolicy(Duration.ofHours(6));

        // then
        assertThat(actual.refreshInterval()).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void 통계_주기가_0이거나_음수거나_없으면_거부한다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> new DemandStatisticsPolicy(Duration.ZERO));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure1 = catchThrowable(() -> new DemandStatisticsPolicy(Duration.ofSeconds(-1)));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure2 = catchThrowable(() -> new DemandStatisticsPolicy(null));

        // then
        assertThat(phaseFailure2)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }
}
