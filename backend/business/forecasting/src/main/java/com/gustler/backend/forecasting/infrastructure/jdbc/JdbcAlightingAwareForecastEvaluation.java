package com.gustler.backend.forecasting.infrastructure.jdbc;

import static com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareIdentifiers.canonical;

import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.evaluation.AlightingAwareForecastEvaluation;
import com.gustler.backend.forecasting.application.evaluation.SettlementRouteRunner;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingPosterior;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvents;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
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
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VERSION = "alighting-capacity-snapshot-v2";
    // 원본 fit에서 제외한 날짜. 이후 공휴일 범위는 새 번들의 입력 계약으로 확장해야 한다.
    private static final Set<LocalDate> FIT_HOLIDAYS = Set.of(
        LocalDate.parse("2026-08-17"), LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-25"),
        LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-09"));
    private static final int PAGE_SIZE = 100;
    private final int pendingLimit;
    private final int observationLimit;
    private final int confirmedObservationLimit;
    private final JdbcClient jdbc;
    private final RouteWorkAccess routeWork;
    private final Clock clock;
    private final SettlementRouteRunner routeRunner;
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

    @Autowired
    public JdbcAlightingAwareForecastEvaluation(
        JdbcClient jdbc,
        RouteWorkAccess routeWork,
        Clock clock,
        PlatformTransactionManager transactions,
        @Value("${forecast.alighting.evaluation.maximum-observation-rows:50000}") final int observationLimit,
        @Value("${forecast.alighting.causal-evaluation.maximum-observation-rows:150000}") final int confirmedObservationLimit,
        @Value("${forecast.pending-limit:3000}") final int pendingLimit
    ) {
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
                LIMIT 1
            ) due ON true
            ORDER BY version.id
            """).query(Long.class).list();
        routeRunner.run(routes, this::settleRoutePages);
    }

    private void settleRoutePages(final long route) {
        int remaining = pendingLimit;
        Instant dueThrough = jdbc.sql("SELECT clock_timestamp()")
            .query(OffsetDateTime.class).single().toInstant();
        while (remaining > 0) {
            final int pageLimit = Math.min(PAGE_SIZE, remaining);
            Integer processed = transaction.execute(status -> settleRoute(route, pageLimit, dueThrough));
            if (processed == null || processed < pageLimit) {
                return;
            }
            remaining -= processed;
        }
    }

    private int settleRoute(final long route, final int limit, Instant dueThrough) {
        final long revision = routeWork.lock(route);
        Instant now = clock.instant();
        List<Work> work = readWork(route, limit, dueThrough);
        Map<LocalDate, List<Work>> days = work.stream().collect(Collectors.groupingBy(w -> day(w.observedAt())));
        for (Map.Entry<LocalDate, List<Work>> entry : days.entrySet()) {
            settleDay(route, revision, now, entry.getKey(), entry.getValue());
        }
        return work.size();
    }

    private void settleDay(
        final long route,
        final long revision,
        Instant now,
        LocalDate date,
        List<Work> work
    ) {
        settleConfirmedDay(route, revision, now, date, work);
        work = work.stream().filter(item -> !canonical(item.version()).equals("alighting-confirmed-capacity-snapshot-v3")).toList();
        if (work.isEmpty()) {
            return;
        }
        Instant through = work.stream().map(w -> w.observedAt().plusSeconds(7200))
            .max(Instant::compareTo).orElse(now);
        if (through.isAfter(now)) {
            through = now;
        }
        List<Evidence> evidence = readObservations(route, date, through);
        final boolean inputLimit = evidence.size() > observationLimit;
        String sourceProblem = null;
        if (!inputLimit) {
            sourceProblem = sourceProblem(route, date, through);
        }
        Map<Long, Evidence> byId = evidence.stream().collect(Collectors.toMap(e -> e.row().observationId(), e -> e));
        Map<List<Integer>, List<AlightingAwarePassEvent>> eventCache = new HashMap<>();
        for (Work item : work) {
            settleWork(item, revision, now, evidence, byId, eventCache, inputLimit, sourceProblem);
        }
    }

    /** 관측 목록은 같은 조회 상한을 쓰는 묶음에서만 보관하고 다음 묶음으로 넘기지 않는다. */
    private void settleConfirmedDay(
        final long route,
        final long revision,
        Instant now,
        LocalDate date,
        List<Work> work
    ) {
        Map<Instant, List<Work>> workByHorizon = work.stream()
            .filter(item -> canonical(item.version()).equals("alighting-confirmed-capacity-snapshot-v3"))
            .collect(Collectors.groupingBy(item -> confirmedThrough(item, now)));
        for (Map.Entry<Instant, List<Work>> entry : workByHorizon.entrySet()) {
            settleConfirmedGroup(route, revision, now, date, entry.getKey(), entry.getValue());
        }
    }

    private Instant confirmedThrough(Work work, Instant now) {
        Instant through = work.observedAt().plusSeconds(7200);
        if (through.isAfter(now)) {
            return now;
        }
        return through;
    }

    private void settleConfirmedGroup(
        final long route,
        final long revision,
        Instant now,
        LocalDate date,
        Instant through,
        List<Work> work
    ) {
        ConfirmedAlightingAwareEvaluation confirmed = new ConfirmedAlightingAwareEvaluation(
            jdbc, route, date, through, confirmedObservationLimit);
        for (Work item : work) {
            settleConfirmed(item, revision, now, confirmed);
        }
    }

    private void settleConfirmed(
        Work work,
        final long revision,
        Instant now,
        ConfirmedAlightingAwareEvaluation confirmed
    ) {
        if (!work.eligible()) {
            finish(work, revision, now, Outcome.unsupported("SOURCE_QUALITY_EXCLUDED"));
            return;
        }
        Outcome outcome = confirmed.evaluate(work.id(), work.stop(), JSON.readTree(work.snapshot()));
        if (outcome == null) {
            defer(work, revision, now, "NO_CLOSED_IDENTITY_SAFE_PASS");
            return;
        }
        finish(work, revision, now, outcome);
    }

    private List<Work> readWork(final long route, final int limit, Instant dueThrough) {
        return jdbc.sql("""
            SELECT w.vehicle_observation_id,w.target_stop_order,w.target_kind,w.label_version,
                   CASE WHEN w.label_version='alighting-confirmed-capacity-snapshot-v3' THEN coalesce(batch.input_confirmed_at,batch.response_received_at)
                     ELSE batch.response_received_at END AS source_at,f.feature_snapshot::text,
                   source.vehicle_id,source.vehicle_trip_key,source.quality_direction,
                   (source.remaining_seats IS NOT NULL) AS forecast_eligible
            FROM alighting_forecast_evaluation_work w
            JOIN seat_forecast f USING(vehicle_observation_id,target_stop_order)
            JOIN operational_observation source ON source.id=w.vehicle_observation_id
            JOIN observation_batch batch ON batch.id=source.observation_batch_id
            WHERE w.route_version_id=:route AND w.next_attempt_at<=:dueThrough
            ORDER BY w.next_attempt_at,w.vehicle_observation_id,w.target_stop_order,w.target_kind
            LIMIT :limit FOR UPDATE OF w SKIP LOCKED
            """).param("route", route).param("limit", limit).param("dueThrough", dueThrough.atOffset(ZoneOffset.UTC))
            .query((rs, n) -> new Work(rs.getLong("vehicle_observation_id"), rs.getInt("target_stop_order"),
                rs.getString("target_kind"), rs.getString("label_version"), route,
                rs.getObject("source_at", OffsetDateTime.class).toInstant(), rs.getString("feature_snapshot"),
                rs.getString("vehicle_id"), rs.getString("vehicle_trip_key"), rs.getLong("quality_direction"),
                rs.getBoolean("forecast_eligible")))
            .list();
    }

    private void settleWork(
        Work work,
        final long revision,
        Instant now,
        List<Evidence> evidence,
        Map<Long, Evidence> byId,
        Map<List<Integer>, List<AlightingAwarePassEvent>> eventCache,
        final boolean inputLimit,
        String sourceProblem
    ) {
        if (!canonical(work.version()).equals(VERSION)) {
            finish(work, revision, now, Outcome.unsupported("MODEL_CONTRACT_RETIRED"));
            return;
        }
        if (inputLimit) {
            finish(work, revision, now, Outcome.unsupported("OBSERVATION_INPUT_LIMIT"));
            return;
        }
        if (sourceProblem != null) {
            finish(work, revision, now, Outcome.unsupported(sourceProblem));
            return;
        }
        if (!work.eligible()) {
            finish(work, revision, now, Outcome.unsupported("SOURCE_QUALITY_EXCLUDED"));
            return;
        }
        Outcome result = evaluate(work, evidence, byId, eventCache);
        if (result == null) {
            defer(work, revision, now, "NO_CLOSED_IDENTITY_SAFE_PASS");
            return;
        }
        finish(work, revision, now, result);
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
        boolean eligible
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
