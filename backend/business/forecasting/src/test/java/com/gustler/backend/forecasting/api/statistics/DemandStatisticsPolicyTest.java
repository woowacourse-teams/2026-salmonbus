package com.gustler.backend.forecasting.api.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        // when & then
        assertThatThrownBy(() -> new DemandStatisticsPolicy(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemandStatisticsPolicy(Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemandStatisticsPolicy(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
