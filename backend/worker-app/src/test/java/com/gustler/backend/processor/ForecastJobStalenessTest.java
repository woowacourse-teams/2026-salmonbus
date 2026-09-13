package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.processor.seatdistribution.RuntimeSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
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

    /** 창 밖에 남은 판의 관측 시각. 한계보다 1초 앞이다. */
    private static final Instant LEFT_BEHIND_AT = NOW.minus(STALENESS).minusSeconds(1);

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

    @Captor
    private ArgumentCaptor<Instant> leftBehindFrom;

    @Captor
    private ArgumentCaptor<Instant> leftBehindUntil;

    private ForecastJob job;
    private ListAppender<ILoggingEvent> forecastLog;

    @BeforeEach
    void 예보_배치를_멈춘_시계로_세운다() {
        forecastLog = startCapturingForecastLog();
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

    @AfterEach
    void 로그_수집을_끝낸다() {
        ((Logger) LoggerFactory.getLogger(ForecastJob.class)).detachAppender(forecastLog);
    }

    @Test
    void 창_밖에_두고_온_판이_있으면_경고를_남긴다() {
        // given
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        when(routeVersionRepository.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_3330));
        when(vehicleTrajectoryRepository.findOldestLeftBehindAt(eq(ROUTE_VERSION_3330), any(), any()))
            .thenReturn(Optional.of(LEFT_BEHIND_AT));

        // when
        job.writeForecasts();

        // then
        assertThat(warningMessages()).singleElement()
            .asString().contains(LEFT_BEHIND_AT.toString());
    }

    @Test
    void 밀려난_판이_없으면_경고를_안_남긴다() {
        // given
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        when(routeVersionRepository.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_3330));
        when(vehicleTrajectoryRepository.findOldestLeftBehindAt(eq(ROUTE_VERSION_3330), any(), any()))
            .thenReturn(Optional.empty());

        // when
        job.writeForecasts();

        // then
        assertThat(warningMessages()).isEmpty();
    }

    /** 밀린 상태는 한 회차에 안 풀린다. 회차마다 남기면 같은 사실이 로그를 덮는다. */
    @Test
    void 같은_상태가_이어지는_동안_경고를_거듭_남기지_않는다() {
        // given
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        when(routeVersionRepository.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_3330));
        when(vehicleTrajectoryRepository.findOldestLeftBehindAt(eq(ROUTE_VERSION_3330), any(), any()))
            .thenReturn(Optional.of(LEFT_BEHIND_AT));

        // when 멈춘 시계라 두 회차가 같은 순간에 돈다
        job.writeForecasts();
        job.writeForecasts();

        // then
        assertThat(warningMessages()).hasSize(1);
    }

    /**
     * 옮겨 넣은 관측은 완료 표시가 영영 안 찍혀서, 아래를 안 끊으면 이관 뒤에 경고가 계속 켜진다.
     * 그 아래 끝을 창의 끝에서 재는 것을 여기서 잡는다.
     */
    @Test
    void 거슬러_보는_폭은_창_뒤로_상한만큼이다() {
        // given
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        when(routeVersionRepository.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_3330));

        // when
        job.writeForecasts();

        // then
        assertThat(capturedLeftBehindWindow()).isEqualTo(new LeftBehindWindow(
            NOW.minus(STALENESS).minus(ForecastProperties.MAX_STALENESS), NOW.minus(STALENESS)));
    }

    /**
     * 폭을 지금에서 재면 창이 상한과 같아질 때 두 끝이 한 시각이 돼 조건이 늘 거짓이 된다.
     * 상한은 검증이 받아 주는 값이라 설정으로 닿는다.
     */
    @Test
    void 창을_상한까지_넓혀도_보는_폭이_안_줄어든다() {
        // given
        ForecastJob widest = jobWith(ForecastProperties.MAX_STALENESS);
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        when(routeVersionRepository.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_3330));

        // when
        widest.writeForecasts();

        // then
        LeftBehindWindow actual = capturedLeftBehindWindow();
        assertThat(Duration.between(actual.from(), actual.until()))
            .isEqualTo(ForecastProperties.MAX_STALENESS);
    }

    @Test
    void 창이_상한일_때도_밀려난_판이_있으면_경고를_남긴다() {
        // given
        ForecastJob widest = jobWith(ForecastProperties.MAX_STALENESS);
        Instant leftBehindAt = NOW.minus(ForecastProperties.MAX_STALENESS).minusSeconds(1);
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        when(routeVersionRepository.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_3330));
        when(vehicleTrajectoryRepository.findOldestLeftBehindAt(eq(ROUTE_VERSION_3330), any(), any()))
            .thenReturn(Optional.of(leftBehindAt));

        // when
        widest.writeForecasts();

        // then
        assertThat(warningMessages()).singleElement()
            .asString().contains(leftBehindAt.toString());
    }

    private LeftBehindWindow capturedLeftBehindWindow() {
        verify(vehicleTrajectoryRepository)
            .findOldestLeftBehindAt(anyLong(), leftBehindFrom.capture(), leftBehindUntil.capture());
        return new LeftBehindWindow(leftBehindFrom.getValue(), leftBehindUntil.getValue());
    }

    private ForecastJob jobWith(
        Duration staleness
    ) {
        return new ForecastJob(
            vehicleTrajectoryRepository,
            routeVersionRepository,
            forecastRuntime,
            forecastBatchWriter,
            new ForecastProperties(
                true, Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofHours(6),
                staleness, 20, 3000, 400),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private record LeftBehindWindow(
        Instant from,
        Instant until
    ) {
    }

    private List<String> warningMessages() {
        return forecastLog.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private static ListAppender<ILoggingEvent> startCapturingForecastLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ForecastJob.class)).addAppender(appender);
        return appender;
    }

    private ForecastProperties properties() {
        return new ForecastProperties(
            true, Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofHours(6),
            STALENESS, 20, 3000, 400);
    }
}
