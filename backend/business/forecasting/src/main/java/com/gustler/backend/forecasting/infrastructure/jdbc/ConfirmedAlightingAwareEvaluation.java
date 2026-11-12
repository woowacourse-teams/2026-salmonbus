package com.gustler.backend.forecasting.infrastructure.jdbc;

import static com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareIdentifiers.canonical;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvents;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingPosterior;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** 확인 시각으로 닫힌 방문과 예보 당시의 차량 정원 근거를 새 정답 판본으로 연결한다. */
final class ConfirmedAlightingAwareEvaluation {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final History history;
    private final LocalDate date;
    private final Instant through;
    private final Map<Long, Long> chains = new HashMap<>();
    private final Map<List<Integer>, List<AlightingAwarePassEvent>> events = new HashMap<>();
    private List<AlightingAwareObservation> rows;
    private final Map<Long, AlightingAwareObservation> byId = new HashMap<>();

    ConfirmedAlightingAwareEvaluation(History history, LocalDate date, Instant through) {
        this.history = history;
        this.date = date;
        this.through = through;
    }

    JdbcAlightingAwareForecastEvaluation.Outcome evaluate(final long sourceId, final int target, JsonNode snapshot) {
        if (!canonical(snapshot.path("inputProfile").asText()).equals(AlightingAwareRouteStatistics.CONFIRMED_PROFILE)) {
            return unsupported("INPUT_PROFILE_MISMATCH");
        }
        if (!snapshot.path("capacityPolicy").asText().equals("vehicle-capacity-snapshot-v1")) {
            return unsupported("CAPACITY_POLICY_MISMATCH");
        }
        load(snapshot.path("turn").asInt());
        if (history.exceedsLimit(rows)) {
            return unsupported("OBSERVATION_INPUT_LIMIT");
        }
        if (history.incompleteAt(through)) {
            return unsupported("SOURCE_HISTORY_INCOMPLETE");
        }
        AlightingAwareObservation source = byId.get(sourceId);
        if (source == null) {
            return unsupported("SOURCE_NOT_IN_CONFIRMED_HISTORY");
        }
        List<Integer> stops = new ArrayList<>();
        snapshot.path("realStopOrders").forEach(value -> stops.add(value.intValue()));
        List<AlightingAwarePassEvent> passes = events.computeIfAbsent(List.copyOf(stops), key -> AlightingAwarePassEvents.resolve(rows, key));
        AlightingAwarePassEvent visit = passes.stream().filter(event -> matches(source, target, event)).findFirst().orElse(null);
        if (visit == null) {
            return null;
        }
        return afterSeats(source, visit, passes, snapshot);
    }

    private void load(final int turn) {
        if (rows != null) {
            return;
        }
        rows = history.rowsAt(through).stream()
            .map(row -> withDirection(row, turn))
            .toList();
        AlightingAwareObservation previous = null;
        long chain = 0;
        for (AlightingAwareObservation row : rows) {
            if (row.startsConfirmedChainAfter(previous)) {
                chain = row.observationId();
            }
            chains.put(row.observationId(), chain);
            byId.put(row.observationId(), row);
            previous = row;
        }
    }

    private static AlightingAwareObservation withDirection(AlightingAwareObservation row, final int turn) {
        if (!isDown(row.stopOrder(), row.state(), turn)) {
            return row;
        }
        return new AlightingAwareObservation(row.observationId(), row.batchId(), row.batchRank(), row.receivedAt(),
            row.vehicleId(), row.passedStopOrder(), row.stopOrder(), row.state(), row.seats(), row.crowd(),
            row.routeVersionId(), row.sourceReceivedAt(), true);
    }

    /** 한 페이지에서 같은 날 관측을 한 번 읽고 평가마다 기존 확정 시각 범위를 적용한다. */
    static final class History {
        private final JdbcClient jdbc;
        private final long route;
        private final Instant start;
        private final Instant through;
        private final int limit;
        private List<AlightingAwareObservation> rows;
        private Optional<Instant> firstIncomplete;

        History(
            JdbcClient jdbc,
            final long route,
            LocalDate date,
            Instant through,
            final int limit
        ) {
            this.jdbc = jdbc;
            this.route = route;
            this.start = date.atStartOfDay(SEOUL).toInstant();
            this.through = through;
            this.limit = limit;
        }

        private List<AlightingAwareObservation> rowsAt(Instant horizon) {
            if (rows == null) {
                rows = WorkerOperationLog.measure("alighting_settlement_history_read", route, this::readRows);
            }
            if (through.equals(horizon)) {
                return rows;
            }
            return rows.stream()
                .filter(row -> !row.receivedAt().isAfter(horizon))
                .toList();
        }

        private boolean exceedsLimit(List<AlightingAwareObservation> observations) {
            return observations.size() > limit;
        }

        private List<AlightingAwareObservation> readRows() {
            // 확정 시각 순으로 한도를 적용해야 앞선 시각의 평가에 필요한 관측이 남는다.
            return jdbc.sql("""
                WITH raw AS (
                    SELECT o.id,o.observation_batch_id,o.vehicle_id,o.passed_stop_order,o.stop_order,o.running_state,
                        o.remaining_seats,o.seat_unknown_reason,o.crowd_level,o.route_version_id,
                        b.input_confirmed_at,b.response_received_at,
                        count(*) OVER (PARTITION BY o.observation_batch_id,o.vehicle_id) AS identity_rows
                    FROM vehicle_observation o JOIN observation_batch b ON b.id=o.observation_batch_id
                    JOIN route_version v ON v.id=b.route_version_id
                    WHERE v.route_id=(SELECT route_id FROM route_version WHERE id=:route)
                      AND b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS' AND b.stored_rows>0
                      AND b.response_received_at>=CAST(:start AS timestamptz)-interval '90 seconds'
                      AND b.response_received_at<=:through
                      AND b.input_confirmed_at>=b.response_received_at
                      AND b.input_confirmed_at<=b.response_received_at+interval '90 seconds'
                      AND b.input_confirmed_at>=:start AND b.input_confirmed_at<=:through
                      AND o.vehicle_id IS NOT NULL
                ), limited AS (
                    SELECT id, observation_batch_id, vehicle_id, passed_stop_order, stop_order, running_state,
                        remaining_seats, seat_unknown_reason, crowd_level, route_version_id,
                        input_confirmed_at, response_received_at
                    FROM raw
                    WHERE identity_rows = 1
                    ORDER BY input_confirmed_at, observation_batch_id, id
                    LIMIT :limit
                )
                SELECT id, observation_batch_id, vehicle_id, passed_stop_order, stop_order, running_state,
                    remaining_seats, seat_unknown_reason, crowd_level, route_version_id,
                    input_confirmed_at, response_received_at
                FROM limited
                ORDER BY vehicle_id, input_confirmed_at, observation_batch_id, id
                """)
                .param("route", route)
                .param("start", offset(start))
                .param("through", offset(through))
                .param("limit", limit + 1)
                .query((rs, index) -> {
                    Integer seats = rs.getObject("remaining_seats", Integer.class);
                    if (rs.getString("seat_unknown_reason") != null || invalidSeats(seats)) {
                        seats = null;
                    }
                    return new AlightingAwareObservation(rs.getLong("id"), rs.getLong("observation_batch_id"), 0,
                        rs.getObject("input_confirmed_at", OffsetDateTime.class).toInstant(),
                        rs.getString("vehicle_id"),
                        rs.getInt("passed_stop_order"), rs.getInt("stop_order"), rs.getInt("running_state"),
                        seats, rs.getInt("crowd_level"), rs.getLong("route_version_id"),
                        rs.getObject("response_received_at", OffsetDateTime.class).toInstant(), false);
                })
                .list();
        }

        private boolean incompleteAt(Instant horizon) {
            if (firstIncomplete == null) {
                Instant checkThrough = through;
                if (exceedsLimit(rows)) {
                    checkThrough = rows.stream()
                        .map(AlightingAwareObservation::receivedAt)
                        .max(Instant::compareTo)
                        .orElseThrow();
                }
                Instant checkedThrough = checkThrough;
                firstIncomplete = WorkerOperationLog.measure("alighting_settlement_integrity_read", route, () -> jdbc.sql("""
                    SELECT min(input_confirmed_at) FROM (
                        SELECT b.input_confirmed_at
                        FROM observation_batch b LEFT JOIN vehicle_observation o ON o.observation_batch_id=b.id
                        JOIN route_version v ON v.id=b.route_version_id
                        WHERE v.route_id=(SELECT route_id FROM route_version WHERE id=:route)
                          AND b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS'
                          AND b.response_received_at>=CAST(:start AS timestamptz)-interval '90 seconds'
                          AND b.response_received_at<=:through
                          AND b.input_confirmed_at>=b.response_received_at
                          AND b.input_confirmed_at<=b.response_received_at+interval '90 seconds'
                          AND b.input_confirmed_at>=:start AND b.input_confirmed_at<=:through
                        GROUP BY b.id,b.stored_rows HAVING count(o.id)<>coalesce(b.stored_rows,0)
                    ) incomplete
                    """)
                    .param("route", route)
                    .param("start", offset(start))
                    .param("through", offset(checkedThrough))
                    .query(OffsetDateTime.class)
                    .optional()
                    .map(OffsetDateTime::toInstant));
            }
            return firstIncomplete.filter(at -> !at.isAfter(horizon)).isPresent();
        }
    }

    private boolean matches(AlightingAwareObservation source, final int target, AlightingAwarePassEvent event) {
        if (event.stopOrder() != target || !event.vehicleId().equals(source.vehicleId())) {
            return false;
        }
        if (!chains.get(source.observationId()).equals(chains.get(event.arrival().observationId()))) {
            return false;
        }
        Instant earliest = source.receivedAt().plusSeconds(10);
        Instant latest = source.receivedAt().plusSeconds(7200);
        return rows.stream().anyMatch(row -> eligibleTargetObservation(row, source, target, earliest, latest));
    }

    private boolean eligibleTargetObservation(
        AlightingAwareObservation row,
        AlightingAwareObservation source,
        final int target,
        Instant earliest,
        Instant latest
    ) {
        return row.vehicleId().equals(source.vehicleId()) && row.passedStopOrder() == target
            && chains.get(row.observationId()).equals(chains.get(source.observationId()))
            && row.receivedAt().isAfter(earliest) && !row.receivedAt().isAfter(latest) && row.seats() != null;
    }

    private JdbcAlightingAwareForecastEvaluation.Outcome afterSeats(
        AlightingAwareObservation source,
        AlightingAwarePassEvent visit,
        List<AlightingAwarePassEvent> passes,
        JsonNode snapshot
    ) {
        Instant departure = visit.departure().receivedAt();
        ZonedDateTime local = departure.atZone(SEOUL);
        AlightingTimeCell cell = AlightingTimeCell.of(local, isHoliday(snapshot, departure));
        final boolean extended = !cell.isRush();
        if (extended && !snapshot.has("extendedSettlement")) {
            return unsupported("OUTSIDE_FITTED_BAND");
        }
        AlightingAwarePassEvent previous = passes.stream()
            .filter(
                event -> event.stopOrder() == visit.stopOrder() && event.confirmedAt().isBefore(visit.confirmedAt()))
            .filter(event -> event.departure().receivedAt().atZone(SEOUL).toLocalDate().equals(local.toLocalDate()))
            .max(Comparator.comparing(AlightingAwarePassEvent::confirmedAt)).orElse(null);
        if (previous == null) {
            return unsupported("NO_PREVIOUS_PASS");
        }
        final double gap = Duration.between(previous.departure().receivedAt(), departure).toNanos() / 60e9;
        if (gap < .2 || gap > 60) {
            return unsupported("UNSUPPORTED_HEADWAY");
        }
        JsonNode parameters = extended ? snapshot.path("extendedSettlement").get(cell.key())
            : findParameters(snapshot, visit.stopOrder(), cell.rushBand());
        final int bin = cell.settlementBin(local);
        if (parameters == null) {
            return unsupported("NO_FITTED_STOP_PARAMETERS");
        }
        int capacity = snapshot.path("capacity").asInt();
        if (capacity < 30 || capacity > 70) {
            return unsupported("CAPACITY_EVIDENCE_MISMATCH");
        }
        if (visit.arrival().seats() > capacity || visit.departure().seats() > capacity) {
            return unsupported("OBSERVATION_EXCEEDS_CAPACITY_SNAPSHOT");
        }
        double[] lambda = new double[8];
        if (parameters.path("lam").size() != lambda.length) {
            return unsupported("INVALID_PARAMETER_DIMENSION");
        }
        for (int i = 0; i < lambda.length; i++) {
            lambda[i] = parameters.path("lam").get(i).asDouble();
        }
        AlightingPosterior.Parameters fitted = new AlightingPosterior.Parameters(lambda,
            parameters.path("p").asDouble(), parameters.path("kappa").asDouble(), parameters.path("r").asDouble());
        final double label = AlightingPosterior.afterSeats(fitted, visit.arrival().seats(),
            visit.departure().seats(),
            capacity, gap, previous.departure().seats() == 0, bin);
        return new JdbcAlightingAwareForecastEvaluation.Outcome("SETTLED", label, null, visit, previous, null,
            digest(parameters.toString()), capacity);
    }

    private static JsonNode findParameters(JsonNode snapshot, final int stop, String band) {
        for (JsonNode parameter : snapshot.path("alighting").path("stops")) {
            if (parameter.path("stop").asInt() == stop && parameter.path("band").asText().equals(band)) {
                return parameter;
            }
        }
        return null;
    }

    private static boolean isHoliday(JsonNode snapshot, Instant at) {
        String day = at.atZone(SEOUL).toLocalDate().toString();
        for (JsonNode holiday : snapshot.path("holidays")) {
            if (holiday.asText().equals(day)) {
                return true;
            }
        }
        return false;
    }

    private static boolean invalidSeats(Integer seats) {
        return seats != null && (seats < 0 || seats > 70);
    }

    private static boolean isDown(final int stop, final int state, final int turn) {
        return stop > turn || (stop == turn && state == 2) || (stop == 1 && state != 2);
    }

    private static OffsetDateTime offset(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    private static JdbcAlightingAwareForecastEvaluation.Outcome unsupported(String reason) {
        return JdbcAlightingAwareForecastEvaluation.Outcome.unsupported(reason);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
