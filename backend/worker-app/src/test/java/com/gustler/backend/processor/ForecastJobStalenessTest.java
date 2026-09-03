package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.processor.seatdistribution.RuntimeSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForecastJobStalenessTest {

    private static final Instant NOW = Instant.parse("2026-09-03T02:30:00Z");
    private static final Duration STALENESS = Duration.ofMinutes(5);
    private static final long ROUTE_VERSION_3330 = 1L;
    private static final long ROUTE_VERSION_1650 = 2L;

    @Mock
    private VehicleTrajectoryRepository vehicleTrajectoryRepository;

    @Mock
    private RouteVersionRepository routeVersionRepository;

    @Mock
    private ForecastRuntime forecastRuntime;

    @Mock
    private ForecastBatchWriter forecastBatchWriter;

    @Mock
    private RuntimeSnapshot runtime;

    @Captor
    private ArgumentCaptor<Instant> notBefore;

    private ForecastJob job;

    @BeforeEach
    void 예보_배치를_멈춘_시계로_세운다() {
        job = new ForecastJob(
            vehicleTrajectoryRepository,
            routeVersionRepository,
            forecastRuntime,
            forecastBatchWriter,
            properties(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 신선도_한계를_지금에서_뒤로_물려_묻는다() {
        // given
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        when(routeVersionRepository.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_3330));

        // when
        job.writeForecasts();

        // then
        verify(vehicleTrajectoryRepository)
            .findBatchesAwaitingForecast(anyLong(), notBefore.capture(), anyInt());
        assertThat(notBefore.getValue()).isEqualTo(NOW.minus(STALENESS));
    }

    @Test
    void 한_회차의_노선들이_같은_한계를_쓴다() {
        // given
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        when(routeVersionRepository.findActiveVersionIds())
            .thenReturn(List.of(ROUTE_VERSION_3330, ROUTE_VERSION_1650));

        // when
        job.writeForecasts();

        // then
        verify(vehicleTrajectoryRepository, times(2))
            .findBatchesAwaitingForecast(anyLong(), notBefore.capture(), anyInt());
        assertThat(notBefore.getAllValues()).containsExactly(
            NOW.minus(STALENESS), NOW.minus(STALENESS));
    }

    /** 창 밖이라 큐가 판을 하나도 안 주면 그 판에는 예보도 완료 표시도 안 붙는다. */
    @Test
    void 창_밖_판만_남으면_판을_하나도_안_연다() {
        // given
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        when(routeVersionRepository.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_3330));
        when(vehicleTrajectoryRepository.findBatchesAwaitingForecast(anyLong(), any(), anyInt()))
            .thenReturn(List.of());

        // when
        job.writeForecasts();

        // then
        verifyNoInteractions(forecastBatchWriter);
    }

    private ForecastProperties properties() {
        return new ForecastProperties(
            true, Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofHours(6),
            STALENESS, 20, 3000, 400);
    }
}
