package com.gustler.backend.forecasting.infrastructure.observability;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.application.pipeline.ForecastPipelineService;
import com.gustler.backend.observations.api.CollectionTiming;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 수집과 예보의 진단 지표를 낸다. scrape는 메모리만 읽는다. */
@Component
@Profile("observability")
public class ForecastMonitoring implements ForecastTelemetry, WorkerOperationLog.Listener {
    private static final String UNKNOWN_ROUTE_NAME = "노선 정보 없음";
    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastMonitoring.class);
    private final MeterRegistry registry;
    private final Counter seatReviews;
    private final Counter seatOutliers;
    private final AtomicLong lastSeatReview = new AtomicLong();
    private final AtomicLong lastSeatOutlier = new AtomicLong();
    private final AtomicLong lastCollectionFailure = new AtomicLong();
    private final AtomicLong lastCollectionRecovery = new AtomicLong();
    private final Counter recoveredCollections;
    private final Clock clock;
    private final JdbcTemplate catalog;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<Long, RouteState> routes = new ConcurrentHashMap<>();
    private final AtomicLong heartbeat = new AtomicLong();
    private volatile Map<Long, String> versionNames = Map.of();
    private volatile Map<String, Long> sourceRoutes = Map.of();
    private volatile Map<Long, Long> versionRoutes = Map.of();

    public ForecastMonitoring(MeterRegistry registry, DataSource dataSource, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        seatReviews = registry.counter("salmonbus.collection.seat.quality", "decision", "REVIEW");
        seatOutliers = registry.counter("salmonbus.collection.seat.quality", "decision", "OUTLIER");
        recoveredCollections = registry.counter("salmonbus.collection.recovered.batches");
        Gauge.builder("salmonbus.collection.failure.last.timestamp", lastCollectionFailure, AtomicLong::get)
            .register(registry);
        Gauge.builder("salmonbus.collection.recovery.last.timestamp", lastCollectionRecovery, AtomicLong::get)
            .register(registry);
        Gauge.builder("salmonbus.collection.seat.quality.last.timestamp", lastSeatReview, AtomicLong::get)
            .tag("decision", "REVIEW").register(registry);
        Gauge.builder("salmonbus.collection.seat.quality.last.timestamp", lastSeatOutlier, AtomicLong::get)
            .tag("decision", "OUTLIER").register(registry);
        catalog = new JdbcTemplate(dataSource);
        catalog.setQueryTimeout(1);
        Gauge.builder("salmonbus.collection.expected.interval.seconds",
            () -> CollectionTiming.intervalSeconds(clock.instant())).register(registry);
        Gauge.builder("salmonbus.monitoring.heartbeat.timestamp", heartbeat, AtomicLong::get).register(registry);
    }

    @PostConstruct
    void listen() {
        WorkerOperationLog.setListener(this);
    }

    @PreDestroy
    void stop() {
        WorkerOperationLog.clearListener(this);
    }

    @Override
    public void collectionSeatQuality(final int reviews, final int outliers) {
        final long recordedAt = clock.instant().getEpochSecond();
        if (reviews > 0) {
            seatReviews.increment(reviews);
            lastSeatReview.accumulateAndGet(recordedAt, Math::max);
        }
        if (outliers > 0) {
            seatOutliers.increment(outliers);
            lastSeatOutlier.accumulateAndGet(recordedAt, Math::max);
        }
    }

    @Override
    public void pending(final long routeVersionId, Instant oldestObservedAt) {
        Long id = versionRoutes.get(routeVersionId);
        RouteState state = stateOf(id);
        if (state != null) {
            state.pendingAge = pendingAgeSeconds(oldestObservedAt);
            state.pendingCheckedAt = clock.instant().getEpochSecond();
        }
    }

    @Override
    public void published(
        final long routeId,
        final long routeVersionId,
        Instant observedAt,
        final int predictions
    ) {
        RouteState state = routes.get(routeId);
        if (state == null) {
            return;
        }
        final double now = clock.instant().toEpochMilli() / 1000.0;
        state.committedAt = now;
        // 예측이 없는 커밋은 배치를 끝낸 것이지만 사용자에게 보일 예보를 낸 것은 아니다.
        if (predictions > 0) {
            state.nonemptyAt = now;
            state.observedAt = observedAt.toEpochMilli() / 1000.0;
        }
    }

    @Override
    public void completed(
        String operation,
        Object route,
        final long nanos,
        final boolean failed
    ) {
        if (failed && operation.startsWith("collection_")) {
            lastCollectionFailure.accumulateAndGet(clock.instant().getEpochSecond(), Math::max);
        }
        String outcome = operationOutcome(failed);
        String key = operation + ":" + outcome;
        if (!timers.containsKey(key) && timers.size() >= 128) {
            return;
        }
        timers.computeIfAbsent(key,
            ignored -> Timer.builder("salmonbus.worker.operation").tags("operation", operation, "outcome", outcome)
                .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(100),
                    Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2),
                    Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
                .register(registry))
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void collectionRecovered(final int batches) {
        if (batches > 0) {
            recoveredCollections.increment(batches);
            lastCollectionRecovery.accumulateAndGet(clock.instant().getEpochSecond(), Math::max);
        }
    }

    @Override
    public void collectionAttempted(String sourceRoute, final long epochSecond) {
        Long id = sourceRoutes.get(sourceRoute);
        RouteState state = stateOf(id);
        if (state != null && Double.isNaN(state.firstAttemptAt)) {
            state.firstAttemptAt = epochSecond;
        }
    }

    @Override
    public void collectionCommitted(String sourceRoute, final long observedEpochSecond, final int usableRows) {
        Long id = sourceRoutes.get(sourceRoute);
        RouteState state = stateOf(id);
        if (state != null) {
            state.collectionAt = observedEpochSecond;
            state.usableRows = usableRows;
        }
    }

    @Override
    public String context(String operation, Object route) {
        if (operation.startsWith("collection_") && route instanceof String source) {
            Long id = sourceRoutes.get(source);
            RouteState state = stateOf(id);
            return " routeName=" + quote(collectionName(state));
        }
        if (operation.equals(ForecastPipelineService.OPERATION) && route instanceof Number version) {
            return " routeName=" + quote(text(versionNames.get(version.longValue()), UNKNOWN_ROUTE_NAME));
        }
        return "";
    }

    /** 활성 노선 카탈로그만 1분마다 조회한다. */
    @Scheduled(fixedDelay = 60000)
    public void refreshCatalogAndHeartbeat() {
        heartbeat.set(clock.instant().getEpochSecond());
        LOGGER.info("event=monitoring_heartbeat component=worker telemetrySchema=1");
        try {
            Map<Long, String> names = new HashMap<>();
            Map<Long, String> versions = new HashMap<>();
            Map<String, Long> sources = new HashMap<>();
            Map<Long, Long> versionIds = new HashMap<>();
            catalog.query("""
                SELECT /* salmonbus:forecast_monitoring.catalog */ r.id, r.source_route_id, r.display_name,
                    v.id AS version_id FROM route r
                JOIN route_version v ON v.route_id = r.id AND v.valid_to IS NULL
                ORDER BY r.id LIMIT 201
                """, row -> {
                names.put(row.getLong("id"), row.getString("display_name"));
                versions.put(row.getLong("version_id"), row.getString("display_name"));
                sources.put(row.getString("source_route_id"), row.getLong("id"));
                versionIds.put(row.getLong("version_id"), row.getLong("id"));
            });
            if (names.size() > 200) {
                throw new IllegalStateException("route limit");
            }
            routes.entrySet().removeIf(entry -> {
                if (entry.getValue().name.equals(names.get(entry.getKey()))) {
                    return false;
                }
                entry.getValue().gauges.forEach(registry::remove);
                return true;
            });
            names.forEach((id, name) -> routes.computeIfAbsent(id, ignored -> registerRoute(id, name)));
            versionNames = Map.copyOf(versions);
            sourceRoutes = Map.copyOf(sources);
            versionRoutes = Map.copyOf(versionIds);
        } catch (RuntimeException failure) {
            registry.counter("salmonbus.monitoring.catalog.failures").increment();
            LOGGER.warn("event=monitoring_catalog_failed exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    private RouteState registerRoute(final long id, String name) {
        RouteState state = new RouteState(name);
        state.gauges = List.of(
            Gauge.builder("salmonbus.collection.first.attempt.timestamp", state, s -> s.firstAttemptAt)
                .tags("route_id", Long.toString(id), "route_name", name).register(registry),
            Gauge.builder("salmonbus.collection.last.success.timestamp", state, s -> s.collectionAt)
                .tags("route_id", Long.toString(id), "route_name", name).register(registry),
            Gauge.builder("salmonbus.collection.usable.rows", state, s -> s.usableRows)
                .tags("route_id", Long.toString(id), "route_name", name).register(registry),
            Gauge.builder("salmonbus.forecast.pending.age.seconds", state, s -> s.pendingAge)
                .tags("route_id", Long.toString(id), "route_name", name).register(registry),
            Gauge.builder("salmonbus.forecast.pending.checked.timestamp", state, s -> s.pendingCheckedAt)
                .tags("route_id", Long.toString(id), "route_name", name).register(registry),
            Gauge.builder("salmonbus.forecast.last.commit.timestamp", state, s -> s.committedAt)
                .tags("route_id", Long.toString(id), "route_name", name).register(registry),
            Gauge.builder("salmonbus.forecast.last.nonempty.timestamp", state, s -> s.nonemptyAt)
                .tags("route_id", Long.toString(id), "route_name", name).register(registry),
            Gauge.builder("salmonbus.forecast.last.input.timestamp", state, s -> s.observedAt)
                .tags("route_id", Long.toString(id), "route_name", name).register(registry));
        return state;
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }

    private static String text(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        return value;
    }

    private static final class RouteState {
        final String name;
        volatile double committedAt = Double.NaN, nonemptyAt = Double.NaN, observedAt = Double.NaN;
        volatile double firstAttemptAt = Double.NaN, collectionAt = Double.NaN, usableRows = Double.NaN,
            pendingAge = Double.NaN, pendingCheckedAt = Double.NaN;
        List<Gauge> gauges = List.of();

        RouteState(String name) {
            this.name = name;
        }
    }

    private RouteState stateOf(Long id) {
        if (id == null) {
            return null;
        }
        return routes.get(id);
    }

    private static String collectionName(RouteState state) {
        if (state == null) {
            return "";
        }
        return state.name;
    }

    private static String operationOutcome(final boolean failed) {
        if (failed) {
            return "failed";
        }
        return "returned";
    }

    private long pendingAgeSeconds(Instant oldestObservedAt) {
        if (oldestObservedAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldestObservedAt, clock.instant()).toSeconds());
    }
}
