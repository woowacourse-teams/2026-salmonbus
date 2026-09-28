package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.publication.PublishPendingForecasts;

import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.publication.RouteStopsQuery;
import com.gustler.backend.forecasting.domain.route.RouteVersionQuery;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import com.gustler.backend.forecasting.api.ForecastPolicy;

import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class PublishPendingForecastsService implements PublishPendingForecasts {

    private static final Logger log = LoggerFactory.getLogger(PublishPendingForecastsService.class);

    private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

    private static final Duration LEFT_BEHIND_LOOKBACK = ForecastPolicy.MAX_STALENESS;

    private final VehicleTrajectoryQuery vehicleTrajectoryQuery;
    private final RouteVersionQuery routeVersions;
    private final RouteStopsQuery routeStops;
    private final ForecastRuntime forecastRuntime;
    private final ForecastBatchWriter forecastBatchWriter;
    private final ForecastPolicy properties;
    private final Clock clock;
    private Instant lastStaleWarningAt;

    @PostConstruct
    void reportModelAvailability() {
        if (forecastRuntime.resolveActive().isEmpty()) {
            log.warn("예보 배치가 켜져 있는데 쓸 계수가 없다. 도는 배포와 올라온 계수의 신원이 맞을 때까지 "
                + "batch 를 하나도 안 연다");
        }
    }

    public PublishPendingForecastsService(
        VehicleTrajectoryQuery vehicleTrajectoryQuery,
        RouteVersionQuery routeVersions,
        RouteStopsQuery routeStops,
        ForecastRuntime forecastRuntime,
        ForecastBatchWriter forecastBatchWriter,
        ForecastPolicy properties,
        Clock clock
    ) {
        this.vehicleTrajectoryQuery = vehicleTrajectoryQuery;
        this.routeVersions = routeVersions;
        this.routeStops = routeStops;
        this.forecastRuntime = forecastRuntime;
        this.forecastBatchWriter = forecastBatchWriter;
        this.properties = properties;
        this.clock = clock;
    }

    /** 실행 회차 전체가 같은 모델 스냅샷과 신선도 기준을 사용한다. */
    @Override
    public void writeForecasts() {
        WorkerOperationLog.run("forecast_tick", "all", this::writeForecastsOnce);
    }

    private void writeForecastsOnce() {
        Optional<RuntimeSnapshot> runtime = WorkerOperationLog.measure("forecast_active_model", "all",
            forecastRuntime::resolveActive);
        if (runtime.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Instant notBefore = now.minus(properties.staleness());
        Instant leftBehindFrom = notBefore.minus(LEFT_BEHIND_LOOKBACK);
        Instant oldestLeftBehind = null;
        for (Long routeVersionId : WorkerOperationLog.measure("forecast_routes", "all",
            routeVersions::findActiveVersionIds)) {
            writeForecastsOf(routeVersionId, notBefore, runtime.get());
            oldestLeftBehind = olderOf(
                oldestLeftBehind, leftBehindAt(routeVersionId, leftBehindFrom, notBefore));
        }
        warnIfLeftBehind(oldestLeftBehind, now);
    }

    private Optional<Instant> leftBehindAt(
        final long routeVersionId,
        Instant from,
        Instant until
    ) {
        return WorkerOperationLog.measure("forecast_stale_batches", routeVersionId,
            () -> vehicleTrajectoryQuery.findOldestLeftBehindAt(routeVersionId, from, until));
    }

    private void warnIfLeftBehind(
        Instant oldestLeftBehind,
        Instant now
    ) {
        if (oldestLeftBehind == null) {
            lastStaleWarningAt = null;
            return;
        }
        if (lastStaleWarningAt != null && now.isBefore(lastStaleWarningAt.plus(WARNING_INTERVAL))) {
            return;
        }
        lastStaleWarningAt = now;
        log.warn("신선도 창보다 오래돼서 예보 없이 두고 가는 판이 남아 있다. 그중 가장 오래된 판의 "
            + "관측 시각={}", oldestLeftBehind.atZone(clock.getZone()));
    }

    private static Instant olderOf(
        Instant kept,
        Optional<Instant> candidate
    ) {
        if (candidate.isEmpty()) {
            return kept;
        }
        if (kept == null || candidate.get().isBefore(kept)) {
            return candidate.get();
        }
        return kept;
    }

    private void writeForecastsOf(
        final long routeVersionId,
        Instant notBefore,
        RuntimeSnapshot runtime
    ) {
        RouteStops stops = WorkerOperationLog.measure("forecast_stops", routeVersionId,
            () -> routeStops.readStops(routeVersionId));
        List<PendingForecastBatch> batches = WorkerOperationLog.measure("forecast_pending_batches", routeVersionId,
            () -> vehicleTrajectoryQuery.findBatchesAwaitingForecast(routeVersionId, notBefore,
                properties.batchLimit()));
        for (PendingForecastBatch batch : batches) {
            WorkerOperationLog.run("forecast_write_and_commit", routeVersionId,
                () -> forecastBatchWriter.writeForecastsOf(batch, stops, runtime));
        }
    }
}
