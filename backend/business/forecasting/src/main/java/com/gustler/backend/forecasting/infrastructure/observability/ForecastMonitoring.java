package com.gustler.backend.forecasting.infrastructure.observability;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.diagnostics.WorkerOperationLog.Listener;
import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationDiagnostics;
import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.evaluation.SettledEvaluation;
import com.gustler.backend.observations.api.CollectionTiming;
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

/** scrape는 메모리만 읽는다. 새로 커밋된 정산 결과를 5분 단위로 묶는다. */
@Component
@Profile("observability")
public class ForecastMonitoring implements ForecastTelemetry, Listener {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastMonitoring.class);
    private final MeterRegistry registry;
    private final Clock clock;
    private final JdbcTemplate catalog;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<Long, RouteState> routes = new ConcurrentHashMap<>();
    private final Map<Key, Totals> accuracy = new HashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong heartbeat = new AtomicLong();
    private volatile Map<Long, String> versionNames = Map.of();
    private volatile Map<String, Long> sourceRoutes = Map.of();
    private volatile Map<Long, Long> versionRoutes = Map.of();

    public ForecastMonitoring(MeterRegistry registry, DataSource dataSource, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        catalog = new JdbcTemplate(dataSource);
        catalog.setQueryTimeout(1);
        Gauge.builder("salmonbus.collection.expected.interval.seconds",
            () -> CollectionTiming.intervalSeconds(clock.instant()))
            .register(registry);
        Gauge.builder("salmonbus.monitoring.heartbeat.timestamp", heartbeat, AtomicLong::get).register(registry);
        Gauge.builder("salmonbus.accuracy.dropped.samples", dropped, AtomicLong::get).register(registry);
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
    public void pending(final long routeVersionId, Instant oldestObservedAt) {
        Long id = versionRoutes.get(routeVersionId);
        RouteState state = stateOf(id);
        if (state != null) {
            state.pendingAge = pendingAgeSeconds(oldestObservedAt);
            state.pendingCheckedAt = clock.instant().getEpochSecond();
        }
    }

    @Override
    public synchronized void settled(List<SettledEvaluation> results) {
        for (SettledEvaluation result : results) {
            EvaluationDiagnostics d = result.diagnostics();
            if (d == null) {
                dropped.incrementAndGet();
                continue;
            }
            Key key = new Key(result.routeId(), result.routeVersionId(), result.targetStopOrder(),
                text(d.routeName(), "노선 정보 없음"), text(d.stopName(), "정류장 정보 없음"),
                text(d.stopId(), "unknown"), text(d.direction(), "unknown"), d.modelDeploymentId(),
                distance(result.stopsToTarget()));
            if (!accuracy.containsKey(key) && accuracy.size() >= 5000) {
                dropped.incrementAndGet();
                continue;
            }
            Totals totals = accuracy.computeIfAbsent(key, ignored -> new Totals());
            totals.completed++;
            final boolean eligible = isEligibleForAccuracy(result);
            if (!eligible) {
                continue;
            }
            if (hasValidExpectedSeats(d.expectedSeats())) {
                totals.maeCount++;
                totals.absoluteError += Math.abs(d.expectedSeats() - result.seatsOnArrival());
            }
            if (hasValidFullChance(d.fullChance())) {
                final double actual = actualFullOutcome(result);
                totals.probabilityCount++;
                totals.brierSum += Math.pow(d.fullChance() - actual, 2);
                totals.predictedFullSum += d.fullChance();
                totals.actualFullCount += (long) actual;
            }
        }
    }

    private boolean isEligibleForAccuracy(SettledEvaluation result) {
        if (result.state() != ScoringState.SETTLED || !result.usableForCalibration()) {
            return false;
        }
        if (!result.targetBoardingAllowed() || result.seatsOnArrival() == null) {
            return false;
        }
        return result.seatsOnArrival() >= 0;
    }

    private boolean hasValidExpectedSeats(Double expectedSeats) {
        if (expectedSeats == null || !Double.isFinite(expectedSeats)) {
            return false;
        }
        return expectedSeats >= 0;
    }

    private boolean hasValidFullChance(double fullChance) {
        if (!Double.isFinite(fullChance) || fullChance < 0) {
            return false;
        }
        return fullChance <= 1;
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
        // 0행 발행은 배치 완료일 수 있지만 사용자에게 제공할 새 예보는 아니다.
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
        String outcome = operationOutcome(failed);
        String key = operation + ":" + outcome;
        if (!timers.containsKey(key) && timers.size() >= 128) {
            return;
        }
        timers.computeIfAbsent(key, ignored -> Timer.builder("salmonbus.worker.operation")
                .tags("operation", operation, "outcome", outcome)
                .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(100),
                    Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2),
                    Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
                .register(registry)).record(nanos, TimeUnit.NANOSECONDS);
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
        if (!(route instanceof Number number)) {
            return "";
        }
        if (usesVersionName(operation)) {
            return nameContext(versionNames.get(number.longValue()));
        }
        if (operation.equals("same_day_read")) {
            RouteState state = routes.get(number.longValue());
            return nameContext(stateName(state));
        }
        return "";
    }

    private boolean usesVersionName(String operation) {
        if (operation.startsWith("forecast_") || operation.startsWith("statistics_")) {
            return true;
        }
        return operation.equals("settlement_pending_forecasts")
            || operation.equals("settlement_arrival_candidates");
    }

    /** 활성 노선 카탈로그만 1분마다 조회한다. 평가 테이블 재스캔은 없다. */
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
                SELECT r.id, r.source_route_id, r.display_name, v.id AS version_id FROM route r
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

    /**
     * 메모리의 정확도 집계를 한 스냅샷으로 꺼내 비우고 기존 key=value 로그로 기록한다. 집계 중 새 결과는 다음 구간에 남긴다.
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void flushAccuracy() {
        Map<Key, Totals> snapshot;
        synchronized (this) {
            snapshot = new HashMap<>(accuracy);
            accuracy.clear();
        }
        snapshot.forEach((key, t) -> LOGGER.info(
            "event=forecast_accuracy telemetrySchema=1 routeId={} routeVersionId={} stopOrder={} "
                        + "routeName={} stopName={} stopId={} direction={} modelDeploymentId={} distance={} "
                        + "completed={} maeCount={} absoluteErrorSum={} probabilityCount={} brierSum={} "
                        + "predictedFullSum={} actualFullCount={}",
            key.routeId, key.version, key.stop, quote(key.routeName), quote(key.stopName), quote(key.stopId),
            quote(key.direction), key.model, key.distance, t.completed, t.maeCount, t.absoluteError,
            t.probabilityCount, t.brierSum, t.predictedFullSum, t.actualFullCount));
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static String text(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        return value;
    }

    private static String distance(final int stops) {
        if (stops <= 3) {
            return "1-3";
        }
        if (stops <= 6) {
            return "4-6";
        }
        return "7-12";
    }
    private record Key(long routeId, long version, int stop, String routeName, String stopName,
        String stopId, String direction, long model, String distance) {
    }
    private static final class Totals {
        long completed, maeCount, probabilityCount, actualFullCount;
        double absoluteError, brierSum, predictedFullSum;
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

    private static String stateName(RouteState state) {
        if (state == null) {
            return null;
        }
        return state.name;
    }

    private static String nameContext(String name) {
        return " routeName=" + quote(text(name, "노선 정보 없음"));
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

    private static double actualFullOutcome(SettledEvaluation result) {
        if (result.seatsOnArrival() == 0) {
            return 1;
        }
        return 0;
    }
}
