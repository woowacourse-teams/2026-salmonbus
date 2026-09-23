package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.api.publication.PublishPendingForecasts;

import com.gustler.backend.forecasting.domain.model.ForecastRuntime;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.RouteStops;
import com.gustler.backend.forecasting.domain.publication.RouteVersionRepository;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryRepository;
import com.gustler.backend.forecasting.api.ForecastPolicy;

import com.gustler.backend.forecasting.domain.model.RuntimeSnapshot;

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

    private final VehicleTrajectoryRepository vehicleTrajectoryRepository;
    private final RouteVersionRepository routeVersionRepository;
    private final ForecastRuntime forecastRuntime;
    private final ForecastBatchWriter forecastBatchWriter;
    private final ForecastPolicy properties;
    private final Clock clock;
    private Instant lastStaleWarningAt;

    @PostConstruct
    void reportModelAvailability() {
        if (forecastRuntime.resolveActive().isEmpty()) {
            log.warn("예보 작업이 활성화됐지만 실행 가능한 모델이 없어 발행을 보류한다");
        }
    }

    public PublishPendingForecastsService(
        VehicleTrajectoryRepository vehicleTrajectoryRepository,
        RouteVersionRepository routeVersionRepository,
        ForecastRuntime forecastRuntime,
        ForecastBatchWriter forecastBatchWriter,
        ForecastPolicy properties,
        Clock clock
    ) {
        this.vehicleTrajectoryRepository = vehicleTrajectoryRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.forecastRuntime = forecastRuntime;
        this.forecastBatchWriter = forecastBatchWriter;
        this.properties = properties;
        this.clock = clock;
    }

    /** 실행 회차 전체가 같은 모델 스냅샷과 신선도 기준을 사용한다. */
    @Override
    public void writeForecasts() {
        Optional<RuntimeSnapshot> runtime = forecastRuntime.resolveActive();
        if (runtime.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Instant notBefore = now.minus(properties.staleness());
        Instant leftBehindFrom = notBefore.minus(LEFT_BEHIND_LOOKBACK);
        Instant oldestLeftBehind = null;
        for (Long routeVersionId : routeVersionRepository.findActiveVersionIds()) {
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
        return vehicleTrajectoryRepository.findOldestLeftBehindAt(routeVersionId, from, until);
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
        log.warn("예보 신선도 한계를 지나 발행하지 못한 수집 배치가 있다. 가장 오래된 관측 시각={}",
            oldestLeftBehind);
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
        RouteStops stops = routeVersionRepository.readStops(routeVersionId);
        List<PendingForecastBatch> batches = vehicleTrajectoryRepository.findBatchesAwaitingForecast(
            routeVersionId, notBefore, properties.batchLimit());
        for (PendingForecastBatch batch : batches) {
            forecastBatchWriter.writeForecastsOf(batch, stops, runtime);
        }
    }
}
