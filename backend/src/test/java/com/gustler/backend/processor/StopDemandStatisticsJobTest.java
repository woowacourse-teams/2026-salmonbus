package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StopDemandStatisticsJobTest {

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final Instant COMPUTED_AT = Instant.parse("2026-08-25T00:00:00Z");
    private static final int STOP_ORDER = 44;

    @Mock
    private RouteVersionRepository routeVersionRepository;

    @Mock
    private StopDemandStatisticsRepository stopDemandStatisticsRepository;

    @Captor
    private ArgumentCaptor<StopDemandGeneration> generation;

    private StopDemandStatisticsJob job;

    @BeforeEach
    void 집계_배치를_멈춘_시계로_세운다() {
        job = new StopDemandStatisticsJob(
            routeVersionRepository,
            stopDemandStatisticsRepository,
            Clock.fixed(COMPUTED_AT, ZoneId.of("Asia/Seoul")));
        when(routeVersionRepository.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_3330));
    }

    @Test
    void 회수된_라벨을_접어_셀로_만든다() {
        // given
        givenHourlyTotals(totals("2026-08-19T08:00+09:00", 5.0, 10));
        givenCurrentRevision(2);

        // when
        job.recomputeStopDemand();

        // then
        assertThat(captured().measurements())
            .extracting(measurement -> measurement.cell().stopOrder())
            .containsExactly(STOP_ORDER);
    }

    @Test
    void 세대_번호는_지난_세대에서_하나_오른다() {
        // given
        givenHourlyTotals(totals("2026-08-19T08:00+09:00", 5.0, 10));
        givenCurrentRevision(2);

        // when
        job.recomputeStopDemand();

        // then
        assertThat(captured().revision()).isEqualTo(3);
    }

    @Test
    void 한_번도_안_돌았으면_첫_세대는_1번이다() {
        // given
        givenHourlyTotals(totals("2026-08-19T08:00+09:00", 5.0, 10));
        givenCurrentRevision(0);

        // when
        job.recomputeStopDemand();

        // then
        assertThat(captured().revision()).isEqualTo(1);
    }

    @Test
    void 회수된_라벨이_없으면_세대를_안_올린다() {
        // given
        givenHourlyTotals();

        // when
        job.recomputeStopDemand();

        // then
        verify(stopDemandStatisticsRepository, never()).append(any());
    }

    @Test
    void 세대는_지금_쓰는_계산_규칙_판으로_남는다() {
        // given
        givenHourlyTotals(totals("2026-08-19T08:00+09:00", 5.0, 10));
        givenCurrentRevision(0);

        // when
        job.recomputeStopDemand();

        // then
        assertThat(captured().calculationVersion())
            .isEqualTo(StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION);
    }

    @Test
    void 라벨을_어느_시각까지_담았는지는_주입받은_시계에서_온다() {
        // given
        givenHourlyTotals(totals("2026-08-19T08:00+09:00", 5.0, 10));
        givenCurrentRevision(0);

        // when
        job.recomputeStopDemand();

        // then
        assertThat(captured().dataUntil()).isEqualTo(COMPUTED_AT);
    }

    private void givenHourlyTotals(
        StopDemandHourlyTotals... hourlyTotals
    ) {
        when(stopDemandStatisticsRepository.readHourlyTotals(anyLong(), any()))
            .thenReturn(List.of(hourlyTotals));
    }

    private void givenCurrentRevision(
        final int revision
    ) {
        when(stopDemandStatisticsRepository.currentRevision(anyLong(), anyString())).thenReturn(revision);
    }

    private StopDemandGeneration captured() {
        verify(stopDemandStatisticsRepository).append(generation.capture());
        return generation.getValue();
    }

    private StopDemandHourlyTotals totals(
        String arrivedHourStart,
        final double fillRateTotal,
        final int sampleCount
    ) {
        return new StopDemandHourlyTotals(
            STOP_ORDER,
            OffsetDateTime.parse(arrivedHourStart).toInstant(),
            fillRateTotal,
            0.0,
            sampleCount,
            sampleCount);
    }
}
