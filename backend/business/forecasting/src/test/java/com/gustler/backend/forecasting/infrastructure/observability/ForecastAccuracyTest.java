package com.gustler.backend.forecasting.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ForecastAccuracyTest {
    private static final ForecastAccuracy.Key KEY = new ForecastAccuracy.Key(1, 2, 4, 6, "1-3");

    @Test
    void 채점됨으로_닫힌_예측의_좌석_오차와_만석_확률_오차를_모은다() {
        // given
        ForecastAccuracy accuracy = new ForecastAccuracy();

        // when
        accuracy.add(List.of(settled(10.0, 0.25, 3.0, 3), settled(1.5, 0.75, 0.0, 0)));

        // then
        assertThat(accuracy.drain()).containsExactly(Map.entry(KEY,
            new ForecastAccuracy.Totals(2, 2, 8.5, 2, 0.125, 1.0, 1)));
    }

    @Test
    void 통과_없이_닫힌_예측은_완료_수에만_센다() {
        // given
        ForecastAccuracy accuracy = new ForecastAccuracy();

        // when
        accuracy.add(List.of(closed(2, 10.0, 0.25, null, null)));

        // then
        assertThat(accuracy.drain().get(KEY)).isEqualTo(new ForecastAccuracy.Totals(1, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void 통과에_짝지어졌어도_정답이_지원_안_함이면_완료_수에만_센다() {
        // given
        ForecastAccuracy accuracy = new ForecastAccuracy();

        // when
        accuracy.add(List.of(closed(2, 10.0, 0.25, null, 0)));

        // then
        assertThat(accuracy.drain().get(KEY)).isEqualTo(new ForecastAccuracy.Totals(1, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void 정답_값이_음수면_오차에_넣지_않는다() {
        // given
        ForecastAccuracy accuracy = new ForecastAccuracy();

        // when
        accuracy.add(List.of(settled(10.0, 0.25, -1.0, 3)));

        // then
        assertThat(accuracy.drain().get(KEY)).isEqualTo(new ForecastAccuracy.Totals(1, 0, 0, 0, 0, 0, 0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, -0.5, Double.POSITIVE_INFINITY})
    void 기대_좌석이_없거나_음수이거나_무한대면_좌석_오차에서만_뺀다(final double expectedSeats) {
        // given
        ForecastAccuracy accuracy = new ForecastAccuracy();

        // when
        accuracy.add(List.of(settled(expectedSeats, 0.25, 3.0, 3)));

        // then
        assertThat(accuracy.drain().get(KEY)).isEqualTo(new ForecastAccuracy.Totals(1, 0, 0, 1, 0.0625, 0.25, 0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, -0.01, 1.01, Double.POSITIVE_INFINITY})
    void 만석_확률이_0과_1_사이가_아니면_확률_오차에서만_뺀다(final double fullProbability) {
        // given
        ForecastAccuracy accuracy = new ForecastAccuracy();

        // when
        accuracy.add(List.of(settled(10.0, fullProbability, 3.0, 3)));

        // then
        assertThat(accuracy.drain().get(KEY)).isEqualTo(new ForecastAccuracy.Totals(1, 1, 7.0, 0, 0, 0, 0));
    }

    @Test
    void 만석_확률의_경계값_0과_1은_확률_오차에_넣는다() {
        // given
        ForecastAccuracy accuracy = new ForecastAccuracy();

        // when
        accuracy.add(List.of(settled(10.0, 0.0, 3.0, 3), settled(10.0, 1.0, 3.0, 0)));

        // then
        assertThat(accuracy.drain().get(KEY)).isEqualTo(new ForecastAccuracy.Totals(2, 2, 14.0, 2, 0.0, 1.0, 1));
    }

    @ParameterizedTest
    @CsvSource({"1,1-3", "3,1-3", "4,4-6", "6,4-6", "7,7-12", "12,7-12"})
    void 목표까지의_정류장_수를_세_구간으로_나눈다(final int stopsToTarget, String distance) {
        // given
        ForecastAccuracy accuracy = new ForecastAccuracy();

        // when
        accuracy.add(List.of(closed(stopsToTarget, 10.0, 0.25, 3.0, 3)));

        // then
        assertThat(accuracy.drain().keySet()).containsExactly(new ForecastAccuracy.Key(1, 2, 4, 6, distance));
    }

    @Test
    void 노선_판본_정류장_모델_배포_거리가_다르면_따로_모은다() {
        // given
        ForecastAccuracy accuracy = new ForecastAccuracy();

        // when
        accuracy.add(List.of(
            new ForecastClosed(1, 2, 6, 4, 2, 10.0, 0.25, 3.0, 3),
            new ForecastClosed(1, 3, 6, 4, 2, 10.0, 0.25, 3.0, 3),
            new ForecastClosed(1, 2, 7, 4, 2, 10.0, 0.25, 3.0, 3),
            new ForecastClosed(1, 2, 6, 5, 2, 10.0, 0.25, 3.0, 3),
            new ForecastClosed(1, 2, 6, 4, 5, 10.0, 0.25, 3.0, 3)));

        // then
        assertThat(accuracy.drain().keySet()).containsExactly(
            new ForecastAccuracy.Key(1, 2, 4, 6, "1-3"),
            new ForecastAccuracy.Key(1, 3, 4, 6, "1-3"),
            new ForecastAccuracy.Key(1, 2, 4, 7, "1-3"),
            new ForecastAccuracy.Key(1, 2, 5, 6, "1-3"),
            new ForecastAccuracy.Key(1, 2, 4, 6, "4-6"));
    }

    @Test
    void 열쇠가_상한만큼_있으면_새_열쇠의_예측은_버린_수에_더하고_있던_열쇠는_계속_모은다() {
        // given
        ForecastAccuracy accuracy = new ForecastAccuracy();
        List<ForecastClosed> filling = new ArrayList<>();
        for (int stop = 1; stop <= ForecastAccuracy.MAXIMUM_KEYS; stop++) {
            filling.add(new ForecastClosed(1, 2, 6, stop, 2, 10.0, 0.25, 3.0, 3));
        }
        accuracy.add(filling);

        // when
        accuracy.add(List.of(
            new ForecastClosed(1, 2, 6, ForecastAccuracy.MAXIMUM_KEYS + 1, 2, 10.0, 0.25, 3.0, 3),
            new ForecastClosed(1, 2, 6, ForecastAccuracy.MAXIMUM_KEYS + 2, 2, 10.0, 0.25, 3.0, 3),
            new ForecastClosed(1, 2, 6, 1, 2, 10.0, 0.25, 3.0, 3)));

        // then
        Map<ForecastAccuracy.Key, ForecastAccuracy.Totals> drained = accuracy.drain();
        assertSoftly(softly -> {
            softly.assertThat(accuracy.dropped()).isEqualTo(2);
            softly.assertThat(drained).hasSize(ForecastAccuracy.MAXIMUM_KEYS);
            softly.assertThat(drained.get(new ForecastAccuracy.Key(1, 2, 1, 6, "1-3")).completed()).isEqualTo(2);
        });
    }

    @Test
    void 꺼내면_합계는_비우고_버린_수는_그대로_둔다() {
        // given
        ForecastAccuracy accuracy = new ForecastAccuracy();
        List<ForecastClosed> filling = new ArrayList<>();
        for (int stop = 1; stop <= ForecastAccuracy.MAXIMUM_KEYS + 1; stop++) {
            filling.add(new ForecastClosed(1, 2, 6, stop, 2, 10.0, 0.25, 3.0, 3));
        }
        accuracy.add(filling);
        accuracy.drain();

        // when
        Map<ForecastAccuracy.Key, ForecastAccuracy.Totals> again = accuracy.drain();

        // then
        assertSoftly(softly -> {
            softly.assertThat(again).isEmpty();
            softly.assertThat(accuracy.dropped()).isOne();
        });
    }

    private static ForecastClosed settled(
        final double expectedSeats,
        final double fullProbability,
        final double labelSeats,
        final int departureSeats
    ) {
        return closed(2, expectedSeats, fullProbability, labelSeats, departureSeats);
    }

    private static ForecastClosed closed(
        final int stopsToTarget,
        final double expectedSeats,
        final double fullProbability,
        Double labelSeats,
        Integer departureSeats
    ) {
        return new ForecastClosed(1, 2, 6, 4, stopsToTarget, expectedSeats, fullProbability, labelSeats,
            departureSeats);
    }
}
