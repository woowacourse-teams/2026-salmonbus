package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class StopDemandStatisticsTest {

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final int REVISION = 3;

    /** 값이 둘뿐인 세대는 표준편차가 둘 사이 거리의 절반이라 z 가 -1 과 +1 로 떨어진다. */
    private static final double LOW_RATE = 0.2;
    private static final double HIGH_RATE = 0.6;
    private static final double TOLERANCE = 1e-6;

    @Test
    void 자기_셀이_있으면_그_세대_안에서_z로_잰다() {
        // given
        StopDemandStatistics statistics = statisticsOf(cell(1, LOW_RATE), cell(5, HIGH_RATE));

        // when
        final double actual = statistics.fillRateScoreAt(5);

        // then
        assertThat(actual).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void 셀_값이_전부_같은_세대는_z가_0이다() {
        // given
        StopDemandStatistics statistics = statisticsOf(cell(1, LOW_RATE), cell(5, LOW_RATE));

        // when
        final double actual = statistics.fillRateScoreAt(1);

        // then
        assertThat(actual).isCloseTo(0.0, within(TOLERANCE));
    }

    @Test
    void 자기_셀이_없으면_반경_4_안의_이웃을_거리_제곱에_반비례하게_섞는다() {
        // given
        StopDemandStatistics statistics = statisticsOf(cell(1, LOW_RATE), cell(2, HIGH_RATE));

        // when
        final double actual = statistics.fillRateScoreAt(3);

        // then
        assertThat(actual).isCloseTo(0.6, within(TOLERANCE));
    }

    @Test
    void 반경_4_밖에만_셀이_있으면_그_세대의_평균_자리를_쓴다() {
        // given
        StopDemandStatistics statistics = statisticsOf(cell(1, LOW_RATE), cell(2, HIGH_RATE));

        // when
        final double actual = statistics.fillRateScoreAt(10);

        // then
        assertThat(actual).isCloseTo(0.0, within(TOLERANCE));
    }

    @Test
    void 셀이_하나도_없는_세대는_어느_자리를_물어도_평균_자리로_답한다() {
        // given
        StopDemandStatistics statistics = statisticsOf();

        // when
        final double actual = statistics.fillRateScoreAt(5);

        // then
        assertThat(actual).isCloseTo(0.0, within(TOLERANCE));
    }

    @Test
    void 자기_셀이_없는_자리는_이웃으로_메웠다고_표시한다() {
        // given
        StopDemandStatistics statistics = statisticsOf(cell(1, LOW_RATE), cell(2, HIGH_RATE));

        // when
        final boolean actual = statistics.isFilledByNeighboursAt(3);

        // then
        assertThat(actual).isTrue();
    }

    @Test
    void 자기_셀이_있는_자리는_그_셀의_값으로_답한다() {
        // given
        StopDemandStatistics statistics = statisticsOf(cell(1, LOW_RATE), cell(2, HIGH_RATE));

        // when
        final boolean actual = statistics.isFilledByNeighboursAt(1);

        // then
        assertThat(actual).isFalse();
    }

    @Test
    void 창_안에_셀이_하나면_그_z가_그대로_구간합이다() {
        // given
        StopDemandStatistics statistics = statisticsOf(cell(1, LOW_RATE), cell(5, HIGH_RATE));

        // when
        final double actual = statistics.netBoardingSegmentScoreOf(5, 2);

        // then
        assertThat(actual).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void 셀이_없는_정류장은_구간합에서_세지_않는다() {
        // given
        StopDemandStatistics statistics = statisticsOf(cell(1, LOW_RATE), cell(5, HIGH_RATE), cell(9, LOW_RATE));

        // when
        final double actual = statistics.netBoardingSegmentScoreOf(5, 5);

        // then
        assertThat(actual).isCloseTo(0.5, within(TOLERANCE));
    }

    @Test
    void 창_안에_셀이_하나도_없으면_이웃_폴백으로_넘긴다() {
        // given
        StopDemandStatistics statistics = statisticsOf(cell(1, LOW_RATE), cell(8, HIGH_RATE));

        // when
        final double actual = statistics.netBoardingSegmentScoreOf(10, 2);

        // then
        assertThat(actual).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void 한_정류장의_셀은_하나다() {
        // given
        List<StopDemandCell> twiceAtSameStop = List.of(cell(1, LOW_RATE), cell(1, HIGH_RATE));

        // when & then
        assertThatThrownBy(() -> new StopDemandStatistics(
            ROUTE_VERSION_3330, TimeSlot.MORNING, REVISION, twiceAtSameStop))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private StopDemandStatistics statisticsOf(
        StopDemandCell... cells
    ) {
        return new StopDemandStatistics(ROUTE_VERSION_3330, TimeSlot.MORNING, REVISION, List.of(cells));
    }

    /** 자리가 찬 비율과 순승차 비율에 같은 값을 넣어 두 축을 같은 픽스처로 본다. */
    private StopDemandCell cell(
        final int stopOrder,
        final double rate
    ) {
        return new StopDemandCell(stopOrder, rate, rate, 10, 2);
    }
}
