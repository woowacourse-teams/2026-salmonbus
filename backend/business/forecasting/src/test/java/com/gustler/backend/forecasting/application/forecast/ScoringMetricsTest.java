package com.gustler.backend.forecasting.application.forecast;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;

import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoringMetricsTest {

    private static final LocalDate DATE = LocalDate.of(2026, 1, 5);
    private static final Instant NOW = Instant.parse("2026-01-05T00:00:00Z");

    @Test
    void 정답_키가_중복되면_마지막_정답의_상태와_사유로_예측_수를_센다() {
        // given
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScoringMetrics metrics = new ScoringMetrics(meters, mock(ScoringWriter.class));
        PassLabel first = label(
                PassLabel.State.SETTLED,
                "label-v1",
                12.0,
                null
        );
        PassLabel last = label(
                PassLabel.State.UNSUPPORTED,
                "label-v2",
                null,
                "NO_PREVIOUS_PASS"
        );
        ScoringResult result = new ScoringResult(
                List.of(first, last),
                List.of(),
                List.of(ClosedForecast.scored(21, first, event()), ClosedForecast.scored(22, last, event()))
        );

        // when
        metrics.results(result);

        // then
        assertSoftly(checks -> {
            checks.assertThat(count(
                    meters,
                    "label",
                    "SETTLED",
                    "none"
            )).isEqualTo(1);
            checks.assertThat(count(
                    meters,
                    "label",
                    "UNSUPPORTED",
                    "NO_PREVIOUS_PASS"
            )).isEqualTo(1);
            checks.assertThat(count(
                    meters,
                    "scored",
                    "UNSUPPORTED",
                    "NO_PREVIOUS_PASS"
            )).isEqualTo(2);
            checks.assertThat(count(
                    meters,
                    "scored",
                    "SETTLED",
                    "none"
            )).isZero();
        });
    }

    @Test
    void 연결된_정답이_결과_목록에_없으면_UNKNOWN으로_예측_수를_센다() {
        // given
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScoringMetrics metrics = new ScoringMetrics(meters, mock(ScoringWriter.class));
        PassLabel missing = label(
                PassLabel.State.SETTLED,
                "label-v1",
                12.0,
                null
        );
        ScoringResult result = new ScoringResult(
                List.of(),
                List.of(),
                List.of(ClosedForecast.scored(21, missing, event()))
        );

        // when
        metrics.results(result);

        // then
        assertSoftly(checks -> {
            checks.assertThat(count(
                    meters,
                    "scored",
                    "UNKNOWN",
                    "none"
            )).isEqualTo(1);
            checks.assertThat(count(
                    meters,
                    "scored",
                    "SETTLED",
                    "none"
            )).isZero();
        });
    }

    private PassLabel label(
        PassLabel.State state,
        String version,
        Double value,
        String reason
    ) {
        return new PassLabel(
                DATE,
                7,
                100,
                3,
                40,
                version,
                9,
                state,
                reason,
                value,
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

    private double count(
        SimpleMeterRegistry meters,
        String kind,
        String state,
        String reason
    ) {
        Counter counter = meters.find("salmonbus.scoring.results")
            .tags(
                    "kind",
                    kind,
                    "state",
                    state,
                    "reason",
                    reason
            )
            .counter();
        if (counter == null) {
            return 0;
        }
        return counter.count();
    }
}
