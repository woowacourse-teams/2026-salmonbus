package com.gustler.backend.forecasting.application.forecast;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoringResultTest {

    private static final LocalDate DATE = LocalDate.of(2026, 1, 5);
    private static final Instant NOW = Instant.parse("2026-01-05T00:00:00Z");

    @Test
    void 빈_결과가_섞여도_저장_순서와_정답_객체_참조를_유지한다() {
        // given
        PassLabel firstLabel = label(100);
        PassLabel secondLabel = label(200);
        UnresolvedForecast lost = new UnresolvedForecast(
                DATE,
                31,
                3,
                7,
                UnresolvedForecast.State.LOST,
                "NO_PASS",
                NOW
        );
        ClosedForecast firstClosed = ClosedForecast.scored(21, firstLabel, event());
        ClosedForecast lostClosed = ClosedForecast.unresolved(lost, event());
        ClosedForecast secondClosed = ClosedForecast.scored(22, secondLabel, event());
        ScoringResult first = new ScoringResult(
                List.of(firstLabel),
                List.of(lost),
                List.of(firstClosed, lostClosed)
        );
        ScoringResult second = new ScoringResult(
                List.of(secondLabel),
                List.of(),
                List.of(secondClosed)
        );

        // when
        ScoringResult result = ScoringResult.combine(List.of(first, ScoringResult.empty(), second));

        // then
        assertSoftly(checks -> {
            checks.assertThat(result.labels()).containsExactly(firstLabel, secondLabel);
            checks.assertThat(result.unresolved()).containsExactly(lost);
            checks.assertThat(result.closed()).containsExactly(firstClosed, lostClosed, secondClosed);
            checks.assertThat(result.closed().get(0).row()).isSameAs(firstLabel);
            checks.assertThat(result.closed().get(1).row()).isSameAs(lost);
            checks.assertThat(result.closed().get(2).row()).isSameAs(secondLabel);
        });
    }

    private PassLabel label(final long tripId) {
        return new PassLabel(
                DATE,
                7,
                tripId,
                3,
                40,
                "label-v1",
                9,
                PassLabel.State.SETTLED,
                null,
                12.0,
                null,
                null,
                null,
                NOW
        );
    }

    private ForecastClosed event() {
        return new ForecastClosed(
                7,
                70,
                9,
                3,
                2,
                12,
                0.1,
                12.0,
                10
        );
    }
}
