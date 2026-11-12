package com.gustler.backend.forecasting.infrastructure.jdbc;

import static com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareIdentifiers.canonical;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.application.AfterCommitTelemetry;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.evaluation.AlightingAwareForecastEvaluation;
import com.gustler.backend.forecasting.application.evaluation.SettlementRouteRunner;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvents;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingPosterior;
import com.gustler.backend.forecasting.infrastructure.ReadWorkBudget;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예보에 기록한 계수로만 평가한다. 기존 관측 정산, DemandSample, 당일 확률 보정에 기록하지 않는다.
 * 페이지당 100개씩 노선별 처리 예산까지 평가한다. 관측 상한 초과와 원자료 누락은 관측 실패와 구분한다.
 */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class JdbcAlightingAwareForecastEvaluation implements AlightingAwareForecastEvaluation {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcAlightingAwareForecastEvaluation.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VERSION = "alighting-capacity-snapshot-v2";
    // 원본 fit에서 제외한 날짜. 이후 공휴일 범위는 새 번들의 입력 계약으로 확장해야 한다.
    private static final Set<LocalDate> FIT_HOLIDAYS = Set.of(
        LocalDate.parse("2026-08-17"), LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-25"),
        LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-09"));
    private static final int PAGE_SIZE = 100;
    private final int pendingLimit;
    private final int preparationLimit;
    private final int maximumPreparations;
    private final int maximumTickSeconds;
    private final AtomicLong lastRoute = new AtomicLong(Long.MIN_VALUE);
    private final int observationLimit;
    private final int confirmedObservationLimit;
    private final JdbcClient jdbc;
    private final RouteWorkAccess routeWork;
    private final Clock clock;
    private final SettlementRouteRunner routeRunner;
    private final TransactionTemplate preparation;
    private final TransactionTemplate transaction;

    public JdbcAlightingAwareForecastEvaluation(
        JdbcClient jdbc,
        RouteWorkAccess routeWork,
        Clock clock,
        PlatformTransactionManager transactions
    ) {
        this(jdbc, routeWork, clock, transactions, 50_000, 150_000);
    }

    public JdbcAlightingAwareForecastEvaluation(
        JdbcClient jdbc,
        RouteWorkAccess routeWork,
        Clock clock,
        PlatformTransactionManager transactions,
        @Value("${forecast.alighting.evaluation.maximum-observation-rows:50000}") final int observationLimit
    ) {
        this(jdbc, routeWork, clock, transactions, observationLimit, observationLimit);
    }

    public JdbcAlightingAwareForecastEvaluation(
        JdbcClient jdbc,
        RouteWorkAccess routeWork,
        Clock clock,
        PlatformTransactionManager transactions,
        @Value("${forecast.alighting.evaluation.maximum-observation-rows:50000}") final int observationLimit,
        @Value("${forecast.alighting.causal-evaluation.maximum-observation-rows:150000}") final int confirmedObservationLimit
    ) {
        this(jdbc, routeWork, clock, transactions, observationLimit, confirmedObservationLimit, 3000);
    }

    public JdbcAlightingAwareForecastEvaluation(
        JdbcClient jdbc,
        RouteWorkAccess routeWork,
        Clock clock,
        PlatformTransactionManager transactions,
        @Value("${forecast.alighting.evaluation.maximum-observation-rows:50000}") final int observationLimit,
        @Value("${forecast.alighting.causal-evaluation.maximum-observation-rows:150000}") final int confirmedObservationLimit,
        @Value("${forecast.pending-limit:3000}") final int pendingLimit
    ) {
        this(jdbc, routeWork, clock, transactions, observationLimit, confirmedObservationLimit, pendingLimit,
            500, 8, 30);
    }

    @Autowired
    public JdbcAlightingAwareForecastEvaluation(
        JdbcClient jdbc, RouteWorkAccess routeWork, Clock clock, PlatformTransactionManager transactions,
        @Value("${forecast.alighting.evaluation.maximum-observation-rows:50000}") int observationLimit,
        @Value("${forecast.alighting.causal-evaluation.maximum-observation-rows:150000}") int confirmedObservationLimit,
        @Value("${forecast.pending-limit:3000}") int pendingLimit,
        @Value("${forecast.alighting.evaluation.preparation-limit:500}") int preparationLimit,
        @Value("${forecast.alighting.evaluation.maximum-preparations-per-tick:8}") int maximumPreparations,
        @Value("${forecast.alighting.evaluation.maximum-tick-seconds:30}") int maximumTickSeconds
    ) {
        if (preparationLimit < PAGE_SIZE || preparationLimit > 3000
            || maximumPreparations < 1 || maximumPreparations > 1000
            || maximumTickSeconds < 1 || maximumTickSeconds > 3600) {
            throw new IllegalArgumentException("Invalid settlement read budget");
        }
        this.preparationLimit = preparationLimit;
        this.maximumPreparations = maximumPreparations;
        this.maximumTickSeconds = maximumTickSeconds;
        if (pendingLimit < 1 || pendingLimit > 100_000) {
            throw new IllegalArgumentException("AlightingAware 회차별 처리 예산은 1~100000개여야 한다");
        }
        this.pendingLimit = pendingLimit;
        if (confirmedObservationLimit < 1 || confirmedObservationLimit > 1_000_000) {
            throw new IllegalArgumentException("확인 시각 정산 관측 한도는 1~1000000개여야 한다");
        }
        this.confirmedObservationLimit = confirmedObservationLimit;
        if (observationLimit < 1 || observationLimit > 1_000_000) {
            throw new IllegalArgumentException("AlightingAware 정산 관측 한도는 1~1000000개여야 한다");
        }
        this.observationLimit = observationLimit;
        this.jdbc = jdbc;
        this.routeWork = routeWork;
        this.clock = clock;
        this.routeRunner = new SettlementRouteRunner(clock, "alighting_settlement_route");
        this.preparation = new TransactionTemplate(transactions);
        this.preparation.setReadOnly(true);
        this.preparation.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.preparation.setTimeout(15);
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(15);
    }

    @Override
    public void settle() {
        // 다음 시각으로 순환하므로 아직 도착하지 않은 오래된 예보가 뒤의 예보를 막지 않는다.
        List<Long> routes = jdbc.sql("""
            SELECT version.id
            FROM route_version version
            JOIN LATERAL (
                SELECT 1
                FROM alighting_forecast_evaluation_work work
                WHERE work.route_version_id = version.id
                  AND work.next_attempt_at <= statement_timestamp()
                ORDER BY work.next_attempt_at, work.vehicle_observation_id, work.target_stop_order, work.target_kind
                LIMIT 1
            ) due ON true
            ORDER BY version.id
            """).query(Long.class).list();
        ReadWorkBudget budget = new ReadWorkBudget(maximumPreparations, Duration.ofSeconds(maximumTickSeconds));
        long after = lastRoute.get();
        List<Long> ordered = new ArrayList<>(routes.size());
        routes.stream().filter(route -> route > after).forEach(ordered::add);
        routes.stream().filter(route -> route <= after).forEach(ordered::add);
        routeRunner.run(ordered, route -> {
            lastRoute.set(route);
            settleRoutePages(route, budget);
        }, budget::available);
    }

    private void settleRoutePages(final long route, ReadWorkBudget budget) {
        int remaining = pendingLimit;
        Instant dueThrough = jdbc.sql("SELECT clock_timestamp()")
            .query(OffsetDateTime.class).single().toInstant();
        while (remaining > 0 && budget.tryAcquire()) {
            final int readLimit = Math.min(preparationLimit, remaining);
            PreparedPage page = WorkerOperationLog.measure("alighting_settlement_prepare", route,
                () -> preparation.execute(status -> preparePage(route, readLimit, dueThrough)));
            if (page == null || page.work().isEmpty()) {
                return;
            }
            int preparedRemaining = page.work().size();
            while (preparedRemaining > 0) {
                final int writeLimit = Math.min(PAGE_SIZE, preparedRemaining);
                Integer processed = WorkerOperationLog.measure("alighting_settlement_commit", route,
                    () -> transaction.execute(status -> writePage(route, writeLimit, dueThrough, page)));
                if (processed == null || processed < writeLimit) {
                    return;
                }
                preparedRemaining -= processed;
                remaining -= processed;
            }
            if (!page.moreWork()) {
                return;
            }
        }
    }

    private PreparedPage preparePage(final long route, final int limit, Instant dueThrough) {
        final long revision = routeWork.current(route);
        final Instant now = clock.instant();
        List<Work> selected = readWork(route, limit, dueThrough, false);
        List<Work> work = preparationScope(selected);
        boolean moreWork = selected.size() == limit || work.size() < selected.size();
        Map<Work, Outcome> outcomes = new HashMap<>();
        Map<LocalDate, List<Work>> days = work.stream().collect(Collectors.groupingBy(w -> day(w.observedAt())));
        for (Map.Entry<LocalDate, List<Work>> entry : days.entrySet()) {
            prepareDay(route, now, entry.getKey(), entry.getValue(), outcomes);
        }
        return new PreparedPage(revision, now, work, outcomes, moreWork);
    }

    private static List<Work> preparationScope(List<Work> work) {
        if (work.isEmpty()) {
            return work;
        }
        if (work.stream().anyMatch(item -> !isConfirmed(item))) {
            return work.subList(0, Math.min(PAGE_SIZE, work.size()));
        }
        LocalDate date = day(work.getFirst().observedAt());
        int end = 1;
        while (end < work.size() && day(work.get(end).observedAt()).equals(date)) {
            end++;
        }
        return work.subList(0, end);
    }

    private int writePage(
        final long route,
        final int limit,
        Instant dueThrough,
        PreparedPage page
    ) {
        if (routeWork.lock(route) != page.revision()) {
            return 0;
        }
        List<Work> locked = readWork(route, limit, dueThrough, true);
        Map<LocalDate, Instant> preparedLegacyHorizons = legacyHorizons(page.work(), page.now());
        Map<LocalDate, Instant> lockedLegacyHorizons = legacyHorizons(locked, page.now());
        int processed = 0;
        int completed = 0;
        for (Work item : locked) {
            if (!page.outcomes().containsKey(item)) {
                continue;
            }
            LocalDate date = day(item.observedAt());
            // 이전 판본은 같은 날짜의 작업 중 가장 늦은 시각까지의 관측을 함께 쓴다.
            if (!isConfirmed(item)
                && !Objects.equals(preparedLegacyHorizons.get(date), lockedLegacyHorizons.get(date))) {
                continue;
            }
            writeOutcome(item, page);
            processed++;
            if (page.outcomes().get(item) != null
                || Duration.between(item.observedAt(), page.now()).compareTo(Duration.ofHours(2)) > 0) {
                completed++;
            }
        }
        int committedProcessed = processed;
        int committedCompleted = completed;
        AfterCommitTelemetry.record(() -> LOGGER.info(
            "event=alighting_settlement_page_committed routeVersionId={} processed={} completed={} deferred={}",
            route, committedProcessed, committedCompleted, committedProcessed - committedCompleted));
        return processed;
    }

    private void writeOutcome(Work item, PreparedPage page) {
        Outcome outcome = page.outcomes().get(item);
        if (outcome == null) {
            defer(item, page.revision(), page.now(), "NO_CLOSED_IDENTITY_SAFE_PASS");
            return;
        }
        finish(item, page.revision(), page.now(), outcome);
    }

    private static Map<LocalDate, Instant> legacyHorizons(List<Work> work, Instant now) {
        return work.stream()
            .filter(item -> !isConfirmed(item))
            .collect(Collectors.toMap(
                item -> day(item.observedAt()),
                item -> evaluationThrough(item, now),
                BinaryOperator.maxBy(Instant::compareTo)));
    }

    private static boolean isConfirmed(Work work) {
        return canonical(work.version()).equals("alighting-confirmed-capacity-snapshot-v3");
    }

    private void prepareDay(
        final long route,
        Instant now,
        LocalDate date,
        List<Work> work,
        Map<Work, Outcome> outcomes
    ) {
        prepareConfirmedDay(route, now, date, work, outcomes);
        List<Work> legacyWork = work.stream()
            .filter(item -> !isConfirmed(item))
            .toList();
        if (legacyWork.isEmpty()) {
            return;
        }
        Instant through = legacyWork.stream()
            .map(item -> evaluationThrough(item, now))
            .max(Instant::compareTo)
            .orElseThrow();
        List<Evidence> evidence = readObservations(route, date, through);
        final boolean inputLimit = evidence.size() > observationLimit;
        String sourceProblem = null;
        if (!inputLimit) {
            sourceProblem = sourceProblem(route, date, through);
        }
        Map<Long, Evidence> byId = evidence.stream().collect(Collectors.toMap(e -> e.row().observationId(), e -> e));
        Map<List<Integer>, List<AlightingAwarePassEvent>> eventCache = new HashMap<>();
        for (Work item : legacyWork) {
            outcomes.put(item, evaluateWork(item, evidence, byId, eventCache, inputLimit, sourceProblem));
        }
    }

    private void prepareConfirmedDay(
        final long route,
        Instant now,
        LocalDate date,
        List<Work> work,
        Map<Work, Outcome> outcomes
    ) {
        Map<Instant, List<Work>> workByHorizon = work.stream()
            .filter(JdbcAlightingAwareForecastEvaluation::isConfirmed)
            .collect(Collectors.groupingBy(item -> evaluationThrough(item, now)));
        if (workByHorizon.isEmpty()) {
            return;
        }
        Instant latest = workByHorizon.keySet().stream().max(Instant::compareTo).orElseThrow();
        ConfirmedAlightingAwareEvaluation.History history = new ConfirmedAlightingAwareEvaluation.History(
            jdbc, route, date, latest, confirmedObservationLimit);
        for (Map.Entry<Instant, List<Work>> entry : workByHorizon.entrySet()) {
            prepareConfirmedGroup(history, date, entry.getKey(), entry.getValue(), outcomes);
        }
    }

    private static Instant evaluationThrough(Work work, Instant now) {
        Instant through = work.observedAt().plusSeconds(7200);
        if (through.isAfter(now)) {
            return now;
        }
        return through;
    }

    private void prepareConfirmedGroup(
        ConfirmedAlightingAwareEvaluation.History history,
        LocalDate date,
        Instant through,
        List<Work> work,
        Map<Work, Outcome> outcomes
    ) {
        ConfirmedAlightingAwareEvaluation confirmed = new ConfirmedAlightingAwareEvaluation(history, date, through);
        for (Work item : work) {
            if (!item.eligible()) {
                outcomes.put(item, Outcome.unsupported("SOURCE_QUALITY_EXCLUDED"));
                continue;
            }
            outcomes.put(item, confirmed.evaluate(item.id(), item.stop(), JSON.readTree(item.snapshot())));
        }
    }

    private List<Work> readWork(
        final long route,
        final int limit,
        Instant dueThrough,
        final boolean lock
    ) {
        // Work references both the forecast and the observation, and each observation has a batch.
        // Select and lock the due keys before joining those required inputs.
        // Keep the route as the leading sort key so a generic plan can use the route-local due index.
        String sql = """
            WITH work_page AS MATERIALIZED (
                SELECT w.vehicle_observation_id, w.target_stop_order, w.target_kind, w.label_version, w.next_attempt_at
                FROM alighting_forecast_evaluation_work w
                WHERE w.route_version_id BETWEEN :route AND :route AND w.next_attempt_at <= :dueThrough
                ORDER BY w.route_version_id, w.next_attempt_at, w.vehicle_observation_id,
                         w.target_stop_order, w.target_kind, w.label_version
                LIMIT :limit
                %s
            ), source_details AS MATERIALIZED (
                SELECT source.*
                FROM (SELECT DISTINCT vehicle_observation_id FROM work_page) ids
                CROSS JOIN LATERAL (
                    SELECT o.id, o.observation_batch_id, o.vehicle_id, o.vehicle_trip_key, o.quality_direction,
                           (o.remaining_seats IS NOT NULL) AS forecast_eligible
                    FROM operational_observation o
                    WHERE o.id = ids.vehicle_observation_id
                    OFFSET 0
                ) source
            ), batch_details AS (
                SELECT batch.*
                FROM (SELECT DISTINCT observation_batch_id FROM source_details) ids
                CROSS JOIN LATERAL (
                    SELECT b.id, b.input_confirmed_at, b.response_received_at
                    FROM observation_batch b
                    WHERE b.id = ids.observation_batch_id
                    OFFSET 0
                ) batch
            ), forecast_details AS MATERIALIZED (
                SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.feature_snapshot,
                       forecast.model_deployment_id,
                       forecast.feature_snapshot = jsonb_build_object('capacity', forecast.feature_snapshot->'capacity')
                           AS compact
                FROM (SELECT DISTINCT vehicle_observation_id, target_stop_order FROM work_page) keys
                CROSS JOIN LATERAL (
                    SELECT f.* FROM seat_forecast f
                    WHERE f.vehicle_observation_id=keys.vehicle_observation_id AND f.target_stop_order=keys.target_stop_order
                    OFFSET 0
                ) forecast
            ), settlement_contexts AS MATERIALIZED (
                SELECT ids.model_deployment_id, deployment.settlement_context->CAST(:route AS text) AS context
                FROM (SELECT DISTINCT model_deployment_id FROM forecast_details WHERE compact) ids
                JOIN model_deployment deployment ON deployment.id=ids.model_deployment_id
            )
            SELECT w.vehicle_observation_id, w.target_stop_order, w.target_kind, w.label_version, w.next_attempt_at,
                   CASE WHEN w.label_version = 'alighting-confirmed-capacity-snapshot-v3'
                       THEN coalesce(batch.input_confirmed_at, batch.response_received_at)
                       ELSE batch.response_received_at END AS source_at,
                   f.compact,
                   (CASE WHEN NOT f.compact THEN f.feature_snapshot
                         WHEN jsonb_exists(context.context->'stops', w.target_stop_order::text)
                         THEN (context.context->'shared') || f.feature_snapshot || jsonb_build_object(
                             'alighting', jsonb_build_object('stops', context.context->'stops'->(w.target_stop_order::text)))
                         END)::text AS feature_snapshot,
                   source.vehicle_id, source.vehicle_trip_key, source.quality_direction, source.forecast_eligible
            FROM work_page w
            JOIN source_details source ON source.id = w.vehicle_observation_id
            JOIN batch_details batch ON batch.id = source.observation_batch_id
            JOIN forecast_details f USING (vehicle_observation_id, target_stop_order)
            LEFT JOIN settlement_contexts context ON context.model_deployment_id=f.model_deployment_id
            ORDER BY w.next_attempt_at, w.vehicle_observation_id, w.target_stop_order, w.target_kind, w.label_version
            """.formatted(lock ? "FOR UPDATE OF w SKIP LOCKED" : "");
        return jdbc.sql(sql)
            .param("route", route)
            .param("limit", limit)
            .param("dueThrough", dueThrough.atOffset(ZoneOffset.UTC))
            .query((rs, n) -> {
                String snapshot = rs.getString("feature_snapshot");
                if (rs.getBoolean("compact") && snapshot == null) {
                    throw new IllegalStateException("예보가 참조하는 모델 판본의 정산 근거가 없다");
                }
                return new Work(rs.getLong("vehicle_observation_id"), rs.getInt("target_stop_order"),
                    rs.getString("target_kind"), rs.getString("label_version"), route,
                    rs.getObject("source_at", OffsetDateTime.class).toInstant(), snapshot,
                    rs.getString("vehicle_id"), rs.getString("vehicle_trip_key"), rs.getLong("quality_direction"),
                    rs.getBoolean("forecast_eligible"), rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant());
            })
            .list();
    }

    private Outcome evaluateWork(
        Work work,
        List<Evidence> evidence,
        Map<Long, Evidence> byId,
        Map<List<Integer>, List<AlightingAwarePassEvent>> eventCache,
        final boolean inputLimit,
        String sourceProblem
    ) {
        if (!canonical(work.version()).equals(VERSION)) {
            return Outcome.unsupported("MODEL_CONTRACT_RETIRED");
        }
        if (inputLimit) {
            return Outcome.unsupported("OBSERVATION_INPUT_LIMIT");
        }
        if (sourceProblem != null) {
            return Outcome.unsupported(sourceProblem);
        }
        if (!work.eligible()) {
            return Outcome.unsupported("SOURCE_QUALITY_EXCLUDED");
        }
        return evaluate(work, evidence, byId, eventCache);
    }

    private List<Evidence> readObservations(final long route, LocalDate date, Instant now) {
        return jdbc.sql("""
            SELECT o.id,o.observation_batch_id,o.vehicle_id,o.vehicle_trip_key,o.quality_direction,
                o.passed_stop_order,o.stop_order,o.running_state,o.remaining_seats,o.crowd_level,
                b.response_received_at
            FROM operational_observation o JOIN observation_batch b ON b.id=o.observation_batch_id
            WHERE o.route_version_id=:route AND b.response_received_at>=:start
                AND b.response_received_at<=:now AND o.vehicle_id IS NOT NULL
            ORDER BY b.response_received_at,o.id LIMIT :limit
            """).param("route", route).param("start", offset(date.atStartOfDay(SEOUL).toInstant()))
            .param("now", offset(now)).param("limit", observationLimit + 1)
            .query((rs, n) -> new Evidence(new AlightingAwareObservation(rs.getLong("id"), rs.getLong("observation_batch_id"), 0,
                rs.getObject("response_received_at", OffsetDateTime.class).toInstant(), rs.getString("vehicle_id"),
                rs.getInt("passed_stop_order"), rs.getInt("stop_order"), rs.getInt("running_state"),
                rs.getObject("remaining_seats", Integer.class), rs.getInt("crowd_level")),
                rs.getString("vehicle_trip_key"), rs.getLong("quality_direction")))
            .list();
    }

    private String sourceProblem(final long route, LocalDate date, Instant through) {
        return jdbc.sql("""
            SELECT CASE WHEN batch.stored_rows IS NULL AND batch.outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY')
                THEN 'SOURCE_HISTORY_UNVERIFIED' ELSE 'SOURCE_HISTORY_INCOMPLETE' END
            FROM observation_batch batch
            LEFT JOIN vehicle_observation observation ON observation.observation_batch_id=batch.id
            WHERE batch.route_version_id=:route AND batch.response_received_at>=:start
                AND batch.response_received_at<=:through
            GROUP BY batch.id,batch.stored_rows,batch.outcome
            HAVING count(observation.id)<>coalesce(batch.stored_rows,0)
                OR (batch.outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY') AND batch.stored_rows IS NULL)
            ORDER BY batch.id LIMIT 1
            """).param("route", route).param("start", offset(date.atStartOfDay(SEOUL).toInstant()))
            .param("through", offset(through)).query(String.class).optional().orElse(null);
    }

    private Outcome evaluate(
        Work work,
        List<Evidence> evidence,
        Map<Long, Evidence> byId,
        Map<List<Integer>, List<AlightingAwarePassEvent>> eventCache
    ) {
        JsonNode snapshot = JSON.readTree(work.snapshot());
        if (work.trip() == null) {
            return null;
        }
        List<Integer> stops = new ArrayList<>();
        for (JsonNode value : snapshot.path("realStopOrders")) {
            stops.add(value.intValue());
        }
        if (stops.isEmpty()) {
            return Outcome.unsupported("MISSING_REAL_STOP_CONTRACT");
        }
        List<AlightingAwarePassEvent> events = eventCache.computeIfAbsent(List.copyOf(stops),
            key -> AlightingAwarePassEvents.resolve(evidence.stream().map(Evidence::row).toList(), key)
                .stream().filter(e -> safeEvent(e, byId)).toList());
        Optional<AlightingAwarePassEvent> match = events.stream().filter(event -> matchesWork(work, event, byId)).findFirst();
        if (match.isEmpty()) {
            return null;
        }
        AlightingAwarePassEvent event = match.get();
        return evaluateAfterAlighting(work, evidence, snapshot, events, event);
    }

    private Outcome evaluateAfterAlighting(
        Work work,
        List<Evidence> evidence,
        JsonNode snapshot,
        List<AlightingAwarePassEvent> events,
        AlightingAwarePassEvent event
    ) {
        Instant departure = event.firstDeparture().receivedAt();
        ZonedDateTime local = departure.atZone(SEOUL);
        final int minute = local.getHour() * 60 + local.getMinute();
        String band = fittedBand(minute);
        if (isOutsideFittedDays(band, local)) {
            return Outcome.unsupported("OUTSIDE_FITTED_BAND", event);
        }
        JsonNode parameters = null;
        for (JsonNode row : snapshot.path("alighting").path("stops")) {
            if (row.path("stop").intValue() == work.stop() && band.equals(row.path("band").asString())) {
                parameters = row;
                break;
            }
        }
        if (parameters == null) {
            return Outcome.unsupported("NO_FITTED_STOP_PARAMETERS", event);
        }
        Optional<AlightingAwarePassEvent> previous = events.stream()
            .filter(previousEvent -> isPreviousPass(work, previousEvent, departure))
            .max(Comparator.comparing(e -> e.firstDeparture().receivedAt()));
        if (previous.isEmpty()) {
            return Outcome.unsupported("NO_PREVIOUS_PASS", event);
        }
        final double gap = Duration.between(previous.get().firstDeparture().receivedAt(), departure).toMillis()
            / 60000.0;
        if (gap <= .2 || gap > 60) {
            return Outcome.unsupported("UNSUPPORTED_HEADWAY", event);
        }
        int capacity = snapshot.path("capacity").asInt();
        if (!snapshot.path("capacityPolicy").asText().equals("vehicle-capacity-snapshot-v1")
            || capacity < 30 || capacity > 70) {
            return Outcome.unsupported("CAPACITY_SNAPSHOT_INVALID", event);
        }
        if (event.arrival().seats() > capacity || event.departure().seats() > capacity) {
            return Outcome.unsupported("OBSERVATION_EXCEEDS_CAPACITY_SNAPSHOT", event);
        }
        return calculatePosterior(event, previous.get(), capacity, parameters, gap, minute, band);
    }

    private Outcome calculatePosterior(
        AlightingAwarePassEvent event,
        AlightingAwarePassEvent previous,
        final int capacity,
        JsonNode parameters,
        final double gap,
        final int minute,
        String band
    ) {
        double[] lambda = new double[8];
        if (parameters.path("lam").size() != 8) {
            return Outcome.unsupported("INVALID_PARAMETER_DIMENSION", event);
        }
        for (int i = 0; i < 8; i++) {
            lambda[i] = parameters.path("lam").get(i).doubleValue();
        }
        AlightingPosterior.Parameters fitted = new AlightingPosterior.Parameters(lambda,
            parameters.path("p").doubleValue(),
            parameters.path("kappa").doubleValue(), parameters.path("r").doubleValue());
        final int cap = capacity;
        final int bin = Math.max(0, Math.min(7, (minute - bandStart(band)) / 30));
        final double label = AlightingPosterior.afterSeats(fitted, Math.min(event.arrival().seats(), cap),
            Math.min(event.departure().seats(), cap), cap, gap, previous.departure().seats() == 0, bin);
        return new Outcome("SETTLED", label, null, event, previous, null, digest(parameters.toString()), cap);
    }

    private static boolean matchesWork(Work work, AlightingAwarePassEvent event, Map<Long, Evidence> byId) {
        return event.stopOrder() == work.stop() && event.vehicleId().equals(work.vehicle())
            && event.firstDeparture().receivedAt().isAfter(work.observedAt())
            && !event.confirmedAt().isAfter(work.observedAt().plusSeconds(7200))
            && identity(work, event.arrival(), byId) && identity(work, event.departure(), byId)
            && identity(work, event.confirmation(), byId);
    }

    private static boolean isPreviousPass(Work work, AlightingAwarePassEvent event, Instant departure) {
        return event.stopOrder() == work.stop() && day(event.firstDeparture().receivedAt()).equals(day(departure))
            && event.firstDeparture().receivedAt().isBefore(departure)
            && !event.confirmedAt().isAfter(work.observedAt().plusSeconds(7200));
    }

    private static String fittedBand(final int minute) {
        if (minute >= 360 && minute < 600) {
            return "morning";
        }
        if (minute >= 1020 && minute < 1260) {
            return "evening";
        }
        return null;
    }

    private static int bandStart(String band) {
        if (band.equals("morning")) {
            return 360;
        }
        return 1020;
    }

    private static boolean isOutsideFittedDays(String band, ZonedDateTime local) {
        return band == null || local.getDayOfWeek().getValue() > 5 || FIT_HOLIDAYS.contains(local.toLocalDate());
    }

    private static boolean safeEvent(AlightingAwarePassEvent event, Map<Long, Evidence> evidence) {
        Evidence pre = evidence.get(event.arrival().observationId());
        Evidence post = evidence.get(event.departure().observationId());
        Evidence closure = evidence.get(event.confirmation().observationId());
        return pre != null && post != null && closure != null && pre.trip() != null
            && pre.trip().equals(post.trip()) && pre.trip().equals(closure.trip())
            && pre.direction() == post.direction() && pre.direction() == closure.direction();
    }

    private static boolean identity(Work work, AlightingAwareObservation observation, Map<Long, Evidence> evidence) {
        Evidence row = evidence.get(observation.observationId());
        return row != null && Objects.equals(work.trip(), row.trip()) && work.direction() == row.direction();
    }

    private void defer(
        Work work,
        final long revision,
        Instant now,
        String reason
    ) {
        if (Duration.between(work.observedAt(), now).compareTo(Duration.ofHours(2)) > 0) {
            finish(work, revision, now, new Outcome("LOST", null, reason, null, null, null, null, null));
            return;
        }
        reschedule(work, reason);
    }

    private void reschedule(Work work, String reason) {
        jdbc.sql(
            """
                UPDATE alighting_forecast_evaluation_work SET next_attempt_at=clock_timestamp()+interval '30 seconds',reason=:reason
                WHERE vehicle_observation_id=:id AND target_stop_order=:stop AND target_kind=:target AND label_version=:version
                """)
            .param("reason", reason).param("id", work.id()).param("stop", work.stop())
            .param("target", work.target()).param("version", work.version()).update();
    }

    private void finish(
        Work work,
        final long revision,
        Instant now,
        Outcome outcome
    ) {
        jdbc.sql(
            """
                INSERT INTO alighting_forecast_evaluation_result(vehicle_observation_id,target_stop_order,target_kind,label_version,
                    route_version_id,scoring_state,label_value,reason,pre_observation_id,post_observation_id,
                    closure_observation_id,previous_post_observation_id,previous_closure_observation_id,capacity_observation_id,trip_key,quality_direction,
                    quality_revision,event_at,known_at,scored_at,parameter_hash,capacity,capacity_policy)
                VALUES(:id,:stop,:target,:version,:route,:state,:label,:reason,:pre,:post,:closure,:previous,:previousClosure,:capacityId,
                    :trip,:direction,:revision,:event,:known,:now,:hash,:capacity,:policy)
                ON CONFLICT(vehicle_observation_id,target_stop_order,target_kind,label_version,quality_revision) DO NOTHING
                """)
            .param("id", work.id()).param("stop", work.stop()).param("target", work.target())
            .param("version", work.version()).param("route", work.route()).param("state", outcome.state())
            .param("label", outcome.label()).param("reason", outcome.reason())
            .param("pre", outcome.preObservationId())
            .param("post", outcome.postObservationId())
            .param("closure", outcome.closureObservationId())
            .param("previous", outcome.previousPostObservationId())
            .param("previousClosure", outcome.previousClosureObservationId())
            .param("capacityId", outcome.capacityObservationId())
            .param("trip", work.trip()).param("direction", work.direction()).param("revision", revision)
            .param("event", outcome.eventAt())
            .param("known", outcome.knownAt()).param("now", offset(now))
            .param("hash", outcome.parameterHash()).param("capacity", outcome.capacity())
            .param("policy", outcome.capacityPolicy()).update();
        jdbc.sql("""
            DELETE FROM alighting_forecast_evaluation_work WHERE vehicle_observation_id=:id AND target_stop_order=:stop
                AND target_kind=:target AND label_version=:version
            """).param("id", work.id()).param("stop", work.stop()).param("target", work.target())
            .param("version", work.version()).update();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static LocalDate day(Instant instant) {
        return instant.atZone(SEOUL).toLocalDate();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record Evidence(AlightingAwareObservation row, String trip, long direction) {
    }

    private record Work(
        long id,
        int stop,
        String target,
        String version,
        long route,
        Instant observedAt,
        String snapshot,
        String vehicle,
        String trip,
        long direction,
        boolean eligible,
        Instant nextAttemptAt
    ) {
    }

    private record PreparedPage(
        long revision, Instant now, List<Work> work, Map<Work, Outcome> outcomes, boolean moreWork
    ) {
    }

    /** 한 목표의 결과와 근거 식별자를 같은 트랜잭션으로 저장하기 위한 출력 계약이다. */
    record Outcome(
        String state,
        Double label,
        String reason,
        AlightingAwarePassEvent event,
        AlightingAwarePassEvent previous,
        AlightingAwareObservation capacityObservation,
        String parameterHash,
        Integer capacity
    ) {
        static Outcome unsupported(String reason) {
            return unsupported(reason, null);
        }

        static Outcome unsupported(String reason, AlightingAwarePassEvent event) {
            return new Outcome("UNSUPPORTED", null, reason, event, null, null, null, null);
        }

        Long preObservationId() {
            return eventObservationId(event, AlightingAwarePassEvent::arrival);
        }

        Long postObservationId() {
            return eventObservationId(event, AlightingAwarePassEvent::departure);
        }

        Long closureObservationId() {
            return eventObservationId(event, AlightingAwarePassEvent::confirmation);
        }

        Long previousPostObservationId() {
            return eventObservationId(previous, AlightingAwarePassEvent::departure);
        }

        Long previousClosureObservationId() {
            return eventObservationId(previous, AlightingAwarePassEvent::confirmation);
        }

        private Long eventObservationId(AlightingAwarePassEvent pass, Function<AlightingAwarePassEvent, AlightingAwareObservation> observation) {
            if (pass == null) {
                return null;
            }
            return observation.apply(pass).observationId();
        }

        Long capacityObservationId() {
            if (capacityObservation == null) {
                return null;
            }
            return capacityObservation.observationId();
        }

        OffsetDateTime eventAt() {
            if (event == null) {
                return null;
            }
            if (event.arrival().confirmedProfile()) {
                return offset(event.departure().receivedAt());
            }
            return offset(event.firstDeparture().receivedAt());
        }

        OffsetDateTime knownAt() {
            if (event == null) {
                return null;
            }
            if (previous != null && previous.confirmedAt().isAfter(event.confirmedAt())) {
                return offset(previous.confirmedAt());
            }
            return offset(event.confirmedAt());
        }

        String capacityPolicy() {
            if (capacity == null) {
                return null;
            }
            return "vehicle-capacity-snapshot-v1";
        }
    }
}
