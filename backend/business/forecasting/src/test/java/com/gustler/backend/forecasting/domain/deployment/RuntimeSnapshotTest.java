package com.gustler.backend.forecasting.domain.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeSnapshotTest {

    private static final ActiveModelDeployment DEPLOYMENT =
        new ActiveModelDeployment(1L, "feature-v1", "release-1", "0".repeat(64));
    private static final Instant DATA_UNTIL = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void 예보_범위가_없는_배포로는_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> new RuntimeSnapshot(DEPLOYMENT, null, input -> null, DATA_UNTIL))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 계수_묶음의_예보_범위로_노선을_담는지_판단한다() {
        // given
        final RuntimeSnapshot runtime = new RuntimeSnapshot(
            DEPLOYMENT, new SupportedForecastScope(List.of("3330")), input -> null, DATA_UNTIL);

        // when
        final boolean covered = runtime.covers("204000057");
        final boolean notCovered = runtime.covers("234000050");

        // then
        assertThat(covered).isTrue();
        assertThat(notCovered).isFalse();
    }
}
