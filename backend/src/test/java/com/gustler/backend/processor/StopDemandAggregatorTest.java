package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class StopDemandAggregatorTest {

    private static final Clock KOREAN_CLOCK =
        Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private static final int STOP_ORDER = 44;
    private static final double TOLERANCE = 1e-6;

    @Test
    void 날짜마다_평균을_내고_날짜끼리_다시_평균한다() {
        // given
        List<StopDemandHourlyTotals> hourlyTotals = List.of(
            totals("2026-08-19T08:00+09:00", 5.0, 0.0, 10.0, 10),
            totals("2026-08-20T08:00+09:00", 1.0, 0.0, 1.0, 1));

        // when
        List<StopDemandMeasurement> actual = StopDemandAggregator.aggregate(hourlyTotals, KOREAN_CLOCK);

        // then
        assertThat(actual.getFirst().cell().averageFillRate()).isCloseTo(0.75, within(TOLERANCE));
    }

    @Test
    void 같은_날의_여러_시각은_관측_수로_가중해_합친다() {
        // given
        List<StopDemandHourlyTotals> hourlyTotals = List.of(
            totals("2026-08-19T08:00+09:00", 5.0, 0.0, 10.0, 10),
            totals("2026-08-19T07:00+09:00", 2.0, 0.0, 2.0, 2));

        // when
        List<StopDemandMeasurement> actual = StopDemandAggregator.aggregate(hourlyTotals, KOREAN_CLOCK);

        // then
        assertThat(actual.getFirst().cell().averageFillRate()).isCloseTo(7.0 / 12, within(TOLERANCE));
    }

    @Test
    void 순승차_비율은_평균의_비율이지_비율의_평균이_아니다() {
        // given
        List<StopDemandHourlyTotals> hourlyTotals = List.of(
            totals("2026-08-19T08:00+09:00", 1.0, 3.0, 10.0, 2),
            totals("2026-08-19T07:00+09:00", 1.5, 1.0, 30.0, 3));

        // when
        List<StopDemandMeasurement> actual = StopDemandAggregator.aggregate(hourlyTotals, KOREAN_CLOCK);

        // then
        assertThat(actual.getFirst().cell().averageNetBoardingRate()).isCloseTo(0.1, within(TOLERANCE));
    }

    @Test
    void 아침_7시_관측은_아침_셀이_된다() {
        // given
        List<StopDemandHourlyTotals> hourlyTotals = List.of(
            totals("2026-08-19T07:00+09:00", 1.0, 0.0, 2.0, 2));

        // when
        List<StopDemandMeasurement> actual = StopDemandAggregator.aggregate(hourlyTotals, KOREAN_CLOCK);

        // then
        assertThat(actual.getFirst().timeSlot()).isEqualTo(TimeSlot.MORNING);
    }

    @Test
    void 저녁_20시_관측은_그_밖_셀이_된다() {
        // given
        List<StopDemandHourlyTotals> hourlyTotals = List.of(
            totals("2026-08-19T20:00+09:00", 1.0, 0.0, 2.0, 2));

        // when
        List<StopDemandMeasurement> actual = StopDemandAggregator.aggregate(hourlyTotals, KOREAN_CLOCK);

        // then
        assertThat(actual.getFirst().timeSlot()).isEqualTo(TimeSlot.OTHER);
    }

    @Test
    void 같은_정류장이라도_시간대가_다르면_셀이_갈린다() {
        // given
        List<StopDemandHourlyTotals> hourlyTotals = List.of(
            totals("2026-08-19T08:00+09:00", 1.0, 0.0, 2.0, 2),
            totals("2026-08-19T12:00+09:00", 1.0, 0.0, 2.0, 2));

        // when
        List<StopDemandMeasurement> actual = StopDemandAggregator.aggregate(hourlyTotals, KOREAN_CLOCK);

        // then
        assertThat(actual).extracting(StopDemandMeasurement::timeSlot)
            .containsExactly(TimeSlot.MORNING, TimeSlot.OTHER);
    }

    @Test
    void 표본_수는_전부_더하고_날짜_수는_날짜를_센다() {
        // given
        List<StopDemandHourlyTotals> hourlyTotals = List.of(
            totals("2026-08-19T08:00+09:00", 1.5, 0.0, 3.0, 3),
            totals("2026-08-19T07:00+09:00", 2.0, 0.0, 4.0, 4),
            totals("2026-08-20T08:00+09:00", 2.5, 0.0, 5.0, 5));

        // when
        List<StopDemandMeasurement> actual = StopDemandAggregator.aggregate(hourlyTotals, KOREAN_CLOCK);

        // then
        assertThat(actual.getFirst().cell())
            .extracting(StopDemandCell::sampleCount, StopDemandCell::dayCount)
            .containsExactly(12, 2);
    }

    private StopDemandHourlyTotals totals(
        String arrivedHourStart,
        final double fillRateTotal,
        final double netBoardingTotal,
        final double capacityTotal,
        final int sampleCount
    ) {
        return new StopDemandHourlyTotals(
            STOP_ORDER,
            OffsetDateTime.parse(arrivedHourStart).toInstant(),
            fillRateTotal,
            netBoardingTotal,
            capacityTotal,
            sampleCount);
    }
}
