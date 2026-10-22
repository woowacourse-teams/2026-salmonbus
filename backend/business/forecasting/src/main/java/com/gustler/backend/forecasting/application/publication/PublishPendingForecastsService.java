package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.api.publication.PublishPendingForecasts;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.RouteStopsQuery;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import com.gustler.backend.forecasting.domain.route.RouteVersionQuery;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 지원 노선의 미발행 배치를 순차 발행하고 노선별 실패를 격리한다.
 * 모델 사용 기록은 실제 커밋 뒤에만 남긴다.
 */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class PublishPendingForecastsService implements PublishPendingForecasts {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublishPendingForecastsService.class);
    private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

    private static final Duration LEFT_BEHIND_LOOKBACK = ForecastPolicy.MAX_STALENESS;

    private final VehicleTrajectoryQuery vehicleTrajectoryQuery;

    private final RouteVersionQuery routeVersions;

    private final RouteStopsQuery routeStops;
    private final ForecastRuntime forecastRuntime;
    private final ForecastBatchWriter forecastBatchWriter;
    private final ForecastPolicy properties;
    private final Clock clock;
    private final ForecastModelUsageLog modelUsage = new ForecastModelUsageLog();
    private ForecastTelemetry telemetry = ForecastTelemetry.NONE;
    private Instant lastStaleWarningAt;

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

    @Autowired(required = false)
    public void setTelemetry(ForecastTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @PostConstruct
    void reportModelAvailability() {
        if (forecastRuntime.resolveActive().isEmpty()) {
            LOGGER.warn("예보 배치가 켜져 있는데 쓸 계수가 없다. 도는 배포와 올라온 계수의 신원이 맞을 때까지 "
                + "batch 를 하나도 안 연다");
        }
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
            if (!writeForecastsOfRouteInTick(routeVersionId, notBefore, runtime.get())) {
                continue;
            }
            oldestLeftBehind = olderOf(
                oldestLeftBehind, leftBehindAt(routeVersionId, leftBehindFrom, notBefore));
        }
        warnIfLeftBehind(oldestLeftBehind, now);
    }

    @Override
    public void writeForecastsOfRoute(final long routeVersionId) {
        WorkerOperationLog.run("forecast_route", routeVersionId, () -> {
            Optional<RuntimeSnapshot> runtime = WorkerOperationLog.measure("forecast_active_model", "all",
                forecastRuntime::resolveActive);
            if (runtime.isEmpty()) {
                return;
            }
            if (!WorkerOperationLog.measure("forecast_routes", "all", routeVersions::findActiveVersionIds)
                    .contains(routeVersionId)) {
                return;
            }
            writeForecastsOfCoveredRoute(routeVersionId, clock.instant().minus(properties.staleness()), runtime.get());
        });
    }

    private boolean writeForecastsOfRouteInTick(
        final long routeVersionId,
        Instant notBefore,
        RuntimeSnapshot runtime
    ) {
        try {
            return WorkerOperationLog.measure("forecast_route", routeVersionId,
                () -> writeForecastsOfCoveredRoute(routeVersionId, notBefore, runtime));
        } catch (RuntimeException alreadyLogged) {
            return false;
        }
    }

    private boolean writeForecastsOfCoveredRoute(
        final long routeVersionId,
        Instant notBefore,
        RuntimeSnapshot runtime
    ) {
        RouteStops stops = WorkerOperationLog.measure("forecast_stops", routeVersionId,
            () -> routeStops.readStops(routeVersionId));
        if (!runtime.covers(stops.sourceRouteId())) {
            return false;
        }
        if (!runtime.model().supportsRoute(stops)) {
            modelUsage.referenceMismatch(stops.sourceRouteId(), stops.routeName(), routeVersionId, runtime);
            return false;
        }
        writeForecastsOf(routeVersionId, stops, notBefore, runtime);
        return true;
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
        LOGGER.warn("신선도 창보다 오래돼서 예보 없이 두고 가는 판이 남아 있다. 그중 가장 오래된 판의 "
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
        RouteStops stops,
        Instant notBefore,
        RuntimeSnapshot runtime
    ) {
        List<PendingForecastBatch> batches = WorkerOperationLog.measure("forecast_pending_batches", routeVersionId,
            () -> vehicleTrajectoryQuery.findBatchesAwaitingForecast(routeVersionId, notBefore,
                properties.batchLimit()));
        // 기존 처리 대상 조회를 재사용한다. 저장 실패 때도 대상이 있었다는 사실을 남긴다.
        Instant oldest = batches.stream()
            .map(PendingForecastBatch::responseReceivedAt)
            .min(Instant::compareTo)
            .orElse(null);
        recordPendingSafely(routeVersionId, oldest);
        for (PendingForecastBatch batch : batches) {
            final int saved = WorkerOperationLog.measure("forecast_write_and_commit", routeVersionId,
                () -> forecastBatchWriter.writeForecastsOf(batch, stops, runtime));
            if (saved > 0) {
                Runnable record = () -> modelUsage.committed(stops.sourceRouteId(), stops.routeName(),
                    routeVersionId, batch.observationBatchId(), runtime);
                // 외부 트랜잭션에 참여했다면 그 트랜잭션의 커밋까지 기다린다.
                recordWhenCommitted(record);
            }
        }
    }

    /** 진단 수신자 실패를 이 호출 안에서 격리하고 다음 배치 발행을 계속한다. */
    private void recordPendingSafely(final long routeVersionId, Instant oldest) {
        try {
            telemetry.pending(routeVersionId, oldest);
        } catch (RuntimeException diagnosticFailure) {
            return;
        }
    }

    private static void recordWhenCommitted(Runnable record) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    record.run();
                }
            });
            return;
        }
        record.run();
    }
}
