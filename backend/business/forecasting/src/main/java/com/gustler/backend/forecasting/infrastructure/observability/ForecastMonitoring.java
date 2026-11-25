package com.gustler.backend.forecasting.infrastructure.observability;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import com.gustler.backend.forecasting.application.forecast.RunForecastsService;
import com.gustler.backend.observations.api.CollectionSchedule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * 수집과 예보 지표를 제공한다. 지표를 조회할 때는 메모리만 읽는다.
 * 새로 저장한 정산 결과를 메모리에 모아 5분마다 {@code event=forecast_accuracy} 로그로 남긴다.
 * 프로세스를 재시작하면 아직 로그로 남기지 않은 집계는 사라진다.
 */
@Component
@Profile("observability")
public class ForecastMonitoring implements ForecastTelemetry, WorkerOperationLog.Listener {

    /** 정확도 집계 로그의 형식. 대시보드가 이 필드 이름으로 읽는다. */
    static final String ACCURACY_LOG_FORMAT =
        "event=forecast_accuracy telemetrySchema=1 routeId={} routeVersionId={} stopOrder={} "
            + "routeName={} stopName={} stopId={} direction={} modelDeploymentId={} distance={} "
            + "completed={} maeCount={} absoluteErrorSum={} probabilityCount={} brierSum={} "
            + "predictedFullSum={} actualFullCount={}";
    private static final String UNKNOWN_ROUTE_NAME = "노선 정보 없음";
    private static final StopLabel UNKNOWN_STOP = new StopLabel(
        "정류장 정보 없음",
        "unknown",
        "unknown"
    );
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
    private final ForecastAccuracy accuracy = new ForecastAccuracy();
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
            () -> CollectionSchedule.intervalSeconds(clock.instant())).register(registry);
        Gauge.builder("salmonbus.monitoring.heartbeat.timestamp", heartbeat, AtomicLong::get).register(registry);
        Gauge.builder("salmonbus.accuracy.dropped.samples", accuracy, ForecastAccuracy::dropped).register(registry);
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
    public void closed(List<ForecastClosed> closed) {
        accuracy.add(closed);
    }

    /** 예측이 없는 커밋은 배치 완료만 반영하고 사용자에게 보이는 예보의 시각은 갱신하지 않는다. */
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
            ignored -> Timer.builder("salmonbus.worker.operation").tags(
                    "operation",
                    operation,
                    "outcome",
                    outcome
            )
                .serviceLevelObjectives(
                        Duration.ofMillis(10),
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofMillis(250),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60)
                )
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
        if (operation.equals(RunForecastsService.OPERATION) && route instanceof Number version) {
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
            Map<Long, String> routeNames = new HashMap<>();
            Map<Long, String> versionNamesById = new HashMap<>();
            Map<String, Long> routeIdsBySourceId = new HashMap<>();
            Map<Long, Long> routeIdsByVersionId = new HashMap<>();
            catalog.query("""
                SELECT /* salmonbus:forecast_monitoring.catalog */ r.id, r.source_route_id, r.display_name,
                    v.id AS version_id FROM route r
                JOIN route_version v ON v.route_id = r.id AND v.valid_to IS NULL
                ORDER BY r.id LIMIT 201
                """, row -> {
                routeNames.put(row.getLong("id"), row.getString("display_name"));
                versionNamesById.put(row.getLong("version_id"), row.getString("display_name"));
                routeIdsBySourceId.put(row.getString("source_route_id"), row.getLong("id"));
                routeIdsByVersionId.put(row.getLong("version_id"), row.getLong("id"));
            });
            if (routeNames.size() > 200) {
                throw new IllegalStateException("route limit");
            }
            refreshRouteGauges(routeNames);
            versionNames = Map.copyOf(versionNamesById);
            sourceRoutes = Map.copyOf(routeIdsBySourceId);
            versionRoutes = Map.copyOf(routeIdsByVersionId);
        } catch (RuntimeException failure) {
            registry.counter("salmonbus.monitoring.catalog.failures").increment();
            LOGGER.warn("event=monitoring_catalog_failed exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    private void refreshRouteGauges(Map<Long, String> routeNames) {
        routes.entrySet().removeIf(entry -> {
            if (entry.getValue().name.equals(routeNames.get(entry.getKey()))) {
                return false;
            }
            entry.getValue().gauges.forEach(registry::remove);
            return true;
        });
        routeNames.forEach((id, name) -> routes.computeIfAbsent(id, ignored -> registerRoute(id, name)));
    }

    private RouteState registerRoute(final long id, String name) {
        RouteState state = new RouteState(name);
        Tags routeTags = Tags.of(
                "route_id",
                Long.toString(id),
                "route_name",
                name
        );
        state.gauges = List.of(
                Gauge.builder("salmonbus.collection.first.attempt.timestamp", state, s -> s.firstAttemptAt)
                    .tags(routeTags).register(registry),
                Gauge.builder("salmonbus.collection.last.success.timestamp", state, s -> s.collectionAt)
                    .tags(routeTags).register(registry),
                Gauge.builder("salmonbus.collection.usable.rows", state, s -> s.usableRows)
                    .tags(routeTags).register(registry),
                Gauge.builder("salmonbus.forecast.pending.age.seconds", state, s -> s.pendingAge)
                    .tags(routeTags).register(registry),
                Gauge.builder("salmonbus.forecast.pending.checked.timestamp", state, s -> s.pendingCheckedAt)
                    .tags(routeTags).register(registry),
                Gauge.builder("salmonbus.forecast.last.commit.timestamp", state, s -> s.committedAt)
                    .tags(routeTags).register(registry),
                Gauge.builder("salmonbus.forecast.last.nonempty.timestamp", state, s -> s.nonemptyAt)
                    .tags(routeTags).register(registry),
                Gauge.builder("salmonbus.forecast.last.input.timestamp", state, s -> s.observedAt)
                    .tags(routeTags).register(registry)
        );
        return state;
    }

    /**
     * 모아 둔 정확도 합계를 꺼내 비우고 키마다 한 행씩 남긴다. 꺼내는 동안 들어온 결과는 다음 회차에 남는다. 노선, 정류장 이름은
     * 이때 한 번 읽으며, 읽지 못하면 이름 자리에 "정보 없음"을 기록한다.
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void flushAccuracy() {
        Map<ForecastAccuracy.Key, ForecastAccuracy.Totals> snapshot = accuracy.drain();
        if (snapshot.isEmpty()) {
            return;
        }
        StopCatalog names = stopCatalog(snapshot.keySet());
        snapshot.forEach((key, totals) -> {
            StopLabel stop = names.stop(key.routeVersionId(), key.stopOrder());
            LOGGER.info(
                    ACCURACY_LOG_FORMAT,
                    key.routeId(),
                    key.routeVersionId(),
                    key.stopOrder(),
                    quote(names.routeName(key.routeVersionId())),
                    quote(stop.name()),
                    quote(stop.stopId()),
                    quote(stop.direction()),
                    key.modelDeploymentId(),
                    key.distance(),
                    totals.completed(),
                    totals.maeCount(),
                    totals.absoluteErrorSum(),
                    totals.probabilityCount(),
                    totals.brierSum(),
                    totals.predictedFullSum(),
                    totals.actualFullCount()
            );
        });
    }

    private StopCatalog stopCatalog(Set<ForecastAccuracy.Key> keys) {
        List<Long> versions = keys.stream()
            .map(ForecastAccuracy.Key::routeVersionId)
            .distinct()
            .sorted()
            .toList();
        Map<Long, String> routeNames = new HashMap<>();
        Map<StopKey, StopLabel> stops = new HashMap<>();
        try {
            catalog.query("""
                SELECT /* salmonbus:forecast_monitoring.stop_names */ v.id, r.display_name, s.stop_order, s.name,
                    s.stop_id, s.direction
                FROM route_version v
                JOIN route r ON r.id = v.route_id
                LEFT JOIN route_stop s ON s.route_version_id = v.id
                WHERE v.id IN (%s)
                """.formatted(String.join(", ", Collections.nCopies(versions.size(), "?"))), row -> {
                routeNames.put(row.getLong("id"), row.getString("display_name"));
                final int stopOrder = row.getInt("stop_order");
                if (!row.wasNull()) {
                    stops.put(new StopKey(row.getLong("id"), stopOrder),
                        new StopLabel(
                            row.getString("name"),
                            row.getString("stop_id"),
                            row.getString("direction")
                        ));
                }
            }, versions.toArray());
        } catch (RuntimeException failure) {
            LOGGER.warn("event=forecast_accuracy_names_failed exceptionType={}", failure.getClass().getSimpleName());
        }
        return new StopCatalog(routeNames, stops);
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

    private record StopKey(long routeVersionId, int stopOrder) {

    }

    private record StopLabel(String name, String stopId, String direction) {

    }

    /** 정확도 로그에 사용할 노선과 정류장 이름이다. 조회에 실패하면 "정보 없음"을 사용한다. */
    private record StopCatalog(
        Map<Long, String> routeNames,
        Map<StopKey, StopLabel> stops
    ) {

        private String routeName(final long routeVersionId) {
            return text(routeNames.get(routeVersionId), UNKNOWN_ROUTE_NAME);
        }

        private StopLabel stop(final long routeVersionId, final int stopOrder) {
            return stops.getOrDefault(new StopKey(routeVersionId, stopOrder), UNKNOWN_STOP);
        }
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
