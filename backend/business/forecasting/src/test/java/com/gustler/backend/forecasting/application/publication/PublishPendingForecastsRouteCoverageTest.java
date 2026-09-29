package com.gustler.backend.forecasting.application.publication;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.RouteStopsQuery;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import com.gustler.backend.forecasting.domain.route.RouteVersionQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublishPendingForecastsRouteCoverageTest {

    private static final Instant NOW = Instant.parse("2026-09-30T02:30:00Z");
    private static final long ROUTE_VERSION_3330 = 1L;
    private static final long ROUTE_VERSION_9007 = 3L;
    private static final RouteStops STOPS_3330 = stopsOf(ROUTE_VERSION_3330, "204000057");
    private static final RouteStops STOPS_9007 = stopsOf(ROUTE_VERSION_9007, "204000070");
    private static final RuntimeSnapshot RUNTIME = new RuntimeSnapshot(
        new ActiveModelDeployment(1L, "feature-v1", "release-1", "0".repeat(64)),
        new SupportedForecastScope(List.of("1650", "3330")),
        input -> null,
        NOW);

    @Mock
    private VehicleTrajectoryQuery vehicleTrajectoryQuery;

    @Mock
    private RouteVersionQuery routeVersions;

    @Mock
    private RouteStopsQuery routeStops;

    @Mock
    private ForecastRuntime forecastRuntime;

    @Mock
    private ForecastBatchWriter forecastBatchWriter;

    private PublishPendingForecastsService job;

    @BeforeEach
    void 예보_작업을_멈춘_시계로_세운다() {
        job = new PublishPendingForecastsService(
            vehicleTrajectoryQuery,
            routeVersions,
            routeStops,
            forecastRuntime,
            forecastBatchWriter,
            new ForecastPolicy(Duration.ofMinutes(5), 20, 3000, 400),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 계수_묶음이_안_담는_노선은_예보도_밀린_배치_확인도_하지_않는다() {
        // given
        final PendingForecastBatch batch = new PendingForecastBatch(10L, ROUTE_VERSION_3330, 1L, NOW.minusSeconds(20));
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(RUNTIME));
        when(routeVersions.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_9007, ROUTE_VERSION_3330));
        when(routeStops.readStops(ROUTE_VERSION_3330)).thenReturn(STOPS_3330);
        when(routeStops.readStops(ROUTE_VERSION_9007)).thenReturn(STOPS_9007);
        when(vehicleTrajectoryQuery.findBatchesAwaitingForecast(eq(ROUTE_VERSION_3330), any(), anyInt()))
            .thenReturn(List.of(batch));
        when(vehicleTrajectoryQuery.findOldestLeftBehindAt(eq(ROUTE_VERSION_3330), any(), any()))
            .thenReturn(Optional.empty());

        // when
        job.writeForecasts();

        // then
        verify(forecastBatchWriter).writeForecastsOf(batch, STOPS_3330, RUNTIME);
        verify(vehicleTrajectoryQuery, never()).findBatchesAwaitingForecast(eq(ROUTE_VERSION_9007), any(), anyInt());
        verify(vehicleTrajectoryQuery, never()).findOldestLeftBehindAt(eq(ROUTE_VERSION_9007), any(), any());
    }

    @Test
    void 여덟_노선_계수_묶음이면_새_노선도_예보한다() {
        // given
        final RuntimeSnapshot eightRouteRuntime = new RuntimeSnapshot(
            new ActiveModelDeployment(2L, "feature-v1", "release-2", "1".repeat(64)),
            new SupportedForecastScope(List.of("1650", "3330", "9007", "9300", "6011", "3000", "5600", "3500")),
            input -> null,
            NOW);
        final PendingForecastBatch batch = new PendingForecastBatch(20L, ROUTE_VERSION_9007, 3L, NOW.minusSeconds(20));
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(eightRouteRuntime));
        when(routeVersions.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_9007));
        when(routeStops.readStops(ROUTE_VERSION_9007)).thenReturn(STOPS_9007);
        when(vehicleTrajectoryQuery.findBatchesAwaitingForecast(eq(ROUTE_VERSION_9007), any(), anyInt()))
            .thenReturn(List.of(batch));
        when(vehicleTrajectoryQuery.findOldestLeftBehindAt(eq(ROUTE_VERSION_9007), any(), any()))
            .thenReturn(Optional.empty());

        // when
        job.writeForecasts();

        // then
        verify(forecastBatchWriter).writeForecastsOf(batch, STOPS_9007, eightRouteRuntime);
    }

    private static RouteStops stopsOf(final long routeVersionId, String sourceRouteId) {
        return new RouteStops(routeVersionId, sourceRouteId, List.of(new RouteStop(routeVersionId, 1, "stop-1", true)));
    }
}
