package com.gustler.backend.forecasting.application.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.pipeline.ForecastPublished.VehicleForecast;
import com.gustler.backend.forecasting.application.pipeline.ShadowForecastComparison.Result;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShadowForecastComparisonTest {
    private static final Instant AT = Instant.parse("2026-11-04T00:00:00Z");

    @Test
    void 관측과_목표마다_두_값을_비트_단위로_대조해_분류한다() {
        // given
        List<SeatForecast> legacy = List.of(
            legacy(1, 3, 4.5, 0.25),
            legacy(1, 4, 3.0, 0.5),
            legacy(2, 5, 1.0, 0.75),
            legacy(3, 6, 2.0, 0.125));
        List<VehicleForecast> fresh = List.of(
            fresh(1, new int[] {3, 4, 5}, new double[] {4.5, Math.nextUp(3.0), 9}, new double[] {0.25, 0.5, 0.5}));

        // when
        ShadowForecastComparison actual = ShadowForecastComparison.compare(legacy, fresh, Set.of(3L));

        // then
        assertThat(actual.count(Result.SAME)).isOne();
        assertThat(actual.count(Result.VALUE_DIFFERS)).isOne();
        assertThat(actual.count(Result.LEGACY_ONLY)).isOne();
        assertThat(actual.count(Result.NEW_ONLY)).isOne();
        assertThat(actual.count(Result.EXCLUDED)).isOne();
        assertThat(actual.mismatches()).isEqualTo(3);
        assertThat(actual.differences()).singleElement()
            .isEqualTo(new ShadowForecastComparison.Difference(1, 4, 3.0, Math.nextUp(3.0), 0.5, 0.5));
    }

    @Test
    void 음의_영과_양의_영은_다른_값으로_본다() {
        // given
        List<SeatForecast> legacy = List.of(legacy(1, 3, 0.0, 0.0));
        List<VehicleForecast> fresh = List.of(fresh(1, new int[] {3}, new double[] {0.0}, new double[] {-0.0}));

        // when
        ShadowForecastComparison actual = ShadowForecastComparison.compare(legacy, fresh, Set.of());

        // then
        assertThat(actual.count(Result.VALUE_DIFFERS)).isOne();
    }

    private static SeatForecast legacy(final long observation, final int target, final double seats, final double full) {
        return new SeatForecast(observation, 1, target, target - 2, 7, 0, full, full, seats, AT);
    }

    private static VehicleForecast fresh(final long observation, int[] targets, double[] seats, double[] full) {
        return new VehicleForecast(observation, 0, "bus", 2, observation, observation, false, 45, false, targets, seats,
            full);
    }
}
