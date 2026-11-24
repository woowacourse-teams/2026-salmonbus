package com.gustler.backend.forecasting.api.forecast;

import static com.gustler.backend.forecasting.support.ForecastSamples.published;
import static com.gustler.backend.forecasting.support.ForecastSamples.skipped;
import static com.gustler.backend.forecasting.support.ForecastSamples.vehicle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.SchemaCheck;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ForecastPublishedTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 15);
    private static final Instant RECEIVED_AT = Instant.parse("2026-11-15T00:00:10Z");

    @Test
    void 목표_정류장과_예측_배열의_길이가_다르면_만들지_않는다() {
        assertThatThrownBy(() -> new ForecastPublished.VehicleForecast(
                1,
                1,
                "bus",
                4,
                1,
                1,
                false,
                45,
                false,
                new int[] {5, 6},
                new double[] {1.0},
                new double[] {0.1, 0.2}
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("길이");
    }

    @Test
    void 목표_정류장이_없거나_겹치면_만들지_않는다() {
        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> new ForecastPublished.VehicleForecast(
                    1,
                    1,
                    "bus",
                    4,
                    1,
                    1,
                    false,
                    45,
                    false,
                    new int[0],
                    new double[0],
                    new double[0]
            )).isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> new ForecastPublished.VehicleForecast(
                    1,
                    1,
                    "bus",
                    4,
                    1,
                    1,
                    false,
                    45,
                    false,
                    new int[] {5, 5},
                    new double[] {1.0, 2.0},
                    new double[] {0.1, 0.2}
            ))
                .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void 만든_뒤에_넘긴_배열이나_꺼낸_배열을_바꿔도_예측은_그대로다() {
        // given
        int[] targets = {5, 6};
        double[] seats = {1.0, 2.0};
        ForecastPublished.VehicleForecast forecast = new ForecastPublished.VehicleForecast(
                1,
                1,
                "bus",
                4,
                1,
                1,
                false,
                45,
                false,
                targets,
                seats,
                new double[] {0.1, 0.2}
        );

        // when
        targets[0] = 99;
        seats[0] = 99.0;
        forecast.expectedSeats()[1] = 99.0;

        // then
        assertSoftly(softly -> {
            softly.assertThat(forecast.targetStopOrders()).containsExactly(5, 6);
            softly.assertThat(forecast.expectedSeats()).containsExactly(1.0, 2.0);
        });
    }

    @Test
    void 발행한_결과만_버스별_예측을_싣는다() {
        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> new ForecastPublished(
                    DAY,
                    1,
                    7,
                    70,
                    RECEIVED_AT,
                    RECEIVED_AT,
                    3L,
                    ForecastOutcome.PUBLISHED,
                    List.of()
            )).isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> new ForecastPublished(
                    DAY,
                    1,
                    7,
                    70,
                    RECEIVED_AT,
                    RECEIVED_AT,
                    3L,
                    ForecastOutcome.LATE,
                    List.of(vehicle(101, 1))
            )).isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(skipped(
                    DAY,
                    1,
                    RECEIVED_AT,
                    ForecastOutcome.LATE
            ).vehicles()).isEmpty();
        });
    }

    @Test
    void 한_배치에_같은_관측의_예측이_둘이면_만들지_않는다() {
        assertThatThrownBy(() -> published(
                DAY,
                1,
                RECEIVED_AT,
                List.of(vehicle(101, 1), vehicle(101, 2))
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("101");
    }

    @Test
    void 예측_수는_버스별_목표_정류장_수의_합이다() {
        // given
        ForecastPublished published = published(
                DAY,
                1,
                RECEIVED_AT,
                List.of(vehicle(101, 1), vehicle(102, 2))
        );

        // when
        final int actual = published.predictionCount();

        // then
        assertThat(actual).isEqualTo(6);
    }

    @Test
    void 처리_결과는_예보_실행_표의_CHECK와_같다() {
        // when
        Set<String> allowed = SchemaCheck.allowedValues("V81__forecast_records.sql", "outcome");

        // then
        assertThat(Arrays.stream(ForecastOutcome.values()).map(Enum::name))
            .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    void 수집_사건에는_수신_시각이_있어야_한다() {
        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> new ObservationBatchSeen(
                    1,
                    7,
                    70,
                    null,
                    CollectionResult.EMPTY,
                    0,
                    0
            ))
                .isInstanceOf(NullPointerException.class);
            softly.assertThatThrownBy(() -> new ObservationBatchSeen(
                    1,
                    7,
                    70,
                    null,
                    CollectionResult.SUCCESS,
                    3,
                    3
            ))
                .isInstanceOf(NullPointerException.class);
        });
    }
}
