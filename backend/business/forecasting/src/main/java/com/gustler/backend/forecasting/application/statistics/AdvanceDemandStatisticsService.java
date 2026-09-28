package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.api.statistics.AdvanceDemandStatistics;
import com.gustler.backend.forecasting.application.statistics.StatisticsStep.Status;
import com.gustler.backend.forecasting.domain.publication.RouteVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 한 tick에 노선 하나의 한 단계만 실행하고 처리 스레드를 반환한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class AdvanceDemandStatisticsService implements AdvanceDemandStatistics {

    private static final Logger log = LoggerFactory.getLogger(AdvanceDemandStatisticsService.class);

    private final DemandStatisticsPipeline pipeline;
    private final RouteVersionRepository routes;
    private final Clock clock;
    private List<Long> versions = List.of();
    private Instant refreshAt = Instant.MIN;
    private int next;
    private final Map<Long, Instant> retryAt = new HashMap<>();
    private final Map<Long, Integer> failures = new HashMap<>();
    private final Map<Long, Instant> progressAt = new HashMap<>();

    public AdvanceDemandStatisticsService(final DemandStatisticsPipeline pipeline, final RouteVersionRepository routes,
        final Clock clock) {
        this.pipeline = pipeline;
        this.routes = routes;
        this.clock = clock;
    }

    @Override
    public void advance() {
        final Instant now = clock.instant();
        if (!now.isBefore(refreshAt)) {
            refreshAt = now.plusSeconds(10);
            versions = routes.findActiveVersionIds().stream().sorted().toList();
            retryAt.keySet().retainAll(versions);
            failures.keySet().retainAll(versions);
            progressAt.keySet().retainAll(versions);
        }
        for (int visited = 0; visited < versions.size(); visited++) {
            final long version = versions.get(Math.floorMod(next++, versions.size()));
            if (now.isBefore(retryAt.getOrDefault(version, Instant.MIN))) {
                continue;
            }
            final long started = System.nanoTime();
            try {
                // 프록시가 commit한 뒤만 성공/진행을 기록한다.
                final StatisticsStep result = pipeline.step(version);
                failures.remove(version);
                retryAt.put(version, now.plusSeconds(retryDelaySeconds(result.status())));
                final boolean progress = result.status() == Status.PROGRESSED
                    && !now.isBefore(progressAt.getOrDefault(version, Instant.MIN));
                if (result.status() == Status.STARTED || result.status() == Status.COMPLETED || progress) {
                    log.info("event=stop_demand_statistics status={} phase={} routeVersionId={} dataUntil={} stepDurationMs={}",
                        result.status(), result.phase(), version, result.dataUntil() == null ? "-"
                            : result.dataUntil().atZone(clock.getZone()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        elapsed(started));
                    progressAt.put(version, now.plusSeconds(60));
                }
            } catch (RuntimeException exception) {
                final int attempts = failures.merge(version, 1, Integer::sum);
                final long delay = Math.min(60, 1L << Math.min(attempts, 6));
                retryAt.put(version, now.plusSeconds(delay));
                throw exception;
            }
            return;
        }
    }

    private static long retryDelaySeconds(final Status status) {
        return switch (status) {
            case IDLE, COMPLETED -> 10;
            case WAITING -> 1;
            case PROGRESSED, STARTED -> 0;
        };
    }

    private static long elapsed(final long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
