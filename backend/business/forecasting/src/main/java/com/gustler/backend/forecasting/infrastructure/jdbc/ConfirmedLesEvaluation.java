package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.model.les.LesAlightingPosterior;
import com.gustler.backend.forecasting.domain.model.les.LesObservation;
import com.gustler.backend.forecasting.domain.model.les.LesPassEvent;
import com.gustler.backend.forecasting.domain.model.les.LesPassEvents;
import com.gustler.backend.forecasting.domain.model.les.LesRouteStatistics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** 확인 시각으로 닫힌 방문과 예보 당시의 차량 정원 근거를 새 정답 판본으로 연결한다. */
final class ConfirmedLesEvaluation {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;
    private final long route;
    private final LocalDate date;
    private final Instant through;
    private final int limit;
    private final Map<Long, Long> chains = new HashMap<>();
    private final Map<List<Integer>, List<LesPassEvent>> events = new HashMap<>();
    private List<LesObservation> rows;
    private Boolean incomplete;

    ConfirmedLesEvaluation(
        JdbcClient jdbc,
        final long route,
        LocalDate date,
        Instant through,
        final int limit
    ) {
        this.jdbc = jdbc;
        this.route = route;
        this.date = date;
        this.through = through;
        this.limit = limit;
    }

    JdbcLesForecastEvaluation.Outcome evaluate(final long sourceId, final int target, String kind, JsonNode snapshot) {
        if (!snapshot.path("inputProfile").asText().equals(LesRouteStatistics.CONFIRMED_PROFILE)) {
            return unsupported("INPUT_PROFILE_MISMATCH");
        }
        if (!snapshot.path("capacityPolicy").asText().equals("observed-vehicle-prefix-max-through-closure-v2")) {
            return unsupported("CAPACITY_POLICY_MISMATCH");
        }
        load(snapshot.path("turn").asInt());
        if (rows.size() > limit) {
            return unsupported("OBSERVATION_INPUT_LIMIT");
        }
        if (incomplete()) {
            return unsupported("SOURCE_HISTORY_INCOMPLETE");
        }
        LesObservation source = rows.stream().filter(row -> row.observationId() == sourceId).findFirst().orElse(null);
        if (source == null) {
            return unsupported("SOURCE_NOT_IN_CONFIRMED_HISTORY");
        }
        List<Integer> stops = new ArrayList<>();
        snapshot.path("realStopOrders").forEach(value -> stops.add(value.intValue()));
        List<LesPassEvent> passes = events.computeIfAbsent(List.copyOf(stops), key -> LesPassEvents.resolve(rows, key));
        LesPassEvent visit = passes.stream().filter(event -> matches(source, target, event)).findFirst().orElse(null);
        if (visit == null) {
            return null;
        }
        if (kind.equals("DEPARTURE_ZERO")) {
            return JdbcLesForecastEvaluation.Outcome.departureZero(visit);
        }
        return afterSeats(source, visit, passes, snapshot);
    }

    private void load(final int turn) {
        if (rows != null) {
            return;
        }
        rows = jdbc.sql("""
            WITH raw AS (
              SELECT o.*,b.input_confirmed_at,b.response_received_at,
                count(*) OVER (PARTITION BY o.observation_batch_id,o.vehicle_id) AS identity_rows
              FROM vehicle_observation o JOIN observation_batch b ON b.id=o.observation_batch_id
              JOIN route_version v ON v.id=b.route_version_id
              WHERE v.route_id=(SELECT route_id FROM route_version WHERE id=:route)
                AND b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS' AND b.stored_rows>0
                AND b.input_confirmed_at>=b.response_received_at
                AND b.input_confirmed_at<=b.response_received_at+interval '90 seconds'
                AND b.input_confirmed_at>=:start AND b.input_confirmed_at<=:through
                AND o.vehicle_id IS NOT NULL
            ) SELECT * FROM raw WHERE identity_rows=1
              ORDER BY vehicle_id,input_confirmed_at,observation_batch_id,id LIMIT :limit
            """).param("route", route).param("start", offset(date.atStartOfDay(SEOUL).toInstant()))
            .param("through", offset(through)).param("limit", limit + 1)
            .query((rs, index) -> {
                Integer seats = rs.getObject("remaining_seats", Integer.class);
                if (rs.getString("seat_unknown_reason") != null || invalidSeats(seats)) {
                    seats = null;
                }
                final int stop = rs.getInt("stop_order");
                final int state = rs.getInt("running_state");
                return new LesObservation(rs.getLong("id"), rs.getLong("observation_batch_id"), 0,
                    rs.getObject("input_confirmed_at", OffsetDateTime.class).toInstant(), rs.getString("vehicle_id"),
                    rs.getInt("passed_stop_order"), stop, state, seats, rs.getInt("crowd_level"),
                    rs.getLong("route_version_id"),
                    rs.getObject("response_received_at", OffsetDateTime.class).toInstant(),
                    isDown(stop, state, turn));
            }).list();
        LesObservation previous = null;
        long chain = 0;
        for (LesObservation row : rows) {
            if (row.startsConfirmedChainAfter(previous)) {
                chain = row.observationId();
            }
            chains.put(row.observationId(), chain);
            previous = row;
        }
    }

    private boolean incomplete() {
        if (incomplete != null) {
            return incomplete;
        }
        incomplete = jdbc.sql("""
            SELECT EXISTS (
              SELECT b.id FROM observation_batch b LEFT JOIN vehicle_observation o ON o.observation_batch_id=b.id
              JOIN route_version v ON v.id=b.route_version_id
              WHERE v.route_id=(SELECT route_id FROM route_version WHERE id=:route)
                AND b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS'
                AND b.input_confirmed_at>=b.response_received_at
                AND b.input_confirmed_at<=b.response_received_at+interval '90 seconds'
                AND b.input_confirmed_at>=:start AND b.input_confirmed_at<=:through
              GROUP BY b.id,b.stored_rows HAVING count(o.id)<>coalesce(b.stored_rows,0)
            )
            """).param("route", route).param("start", offset(date.atStartOfDay(SEOUL).toInstant()))
            .param("through", offset(through)).query(Boolean.class).single();
        return incomplete;
    }

    private boolean matches(LesObservation source, final int target, LesPassEvent event) {
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
        LesObservation row,
        LesObservation source,
        final int target,
        Instant earliest,
        Instant latest
    ) {
        return row.vehicleId().equals(source.vehicleId()) && row.passedStopOrder() == target
            && chains.get(row.observationId()).equals(chains.get(source.observationId()))
            && row.receivedAt().isAfter(earliest) && !row.receivedAt().isAfter(latest) && row.seats() != null;
    }

    private JdbcLesForecastEvaluation.Outcome afterSeats(
        LesObservation source,
        LesPassEvent visit,
        List<LesPassEvent> passes,
        JsonNode snapshot
    ) {
        Instant departure = visit.departure().receivedAt();
        final int minute = departure.atZone(SEOUL).getHour() * 60 + departure.atZone(SEOUL).getMinute();
        String band = band(minute);
        if (band.isEmpty() || departure.atZone(SEOUL).getDayOfWeek().getValue() > 5 || isHoliday(snapshot, departure)) {
            return unsupported("OUTSIDE_FITTED_BAND");
        }
        LesPassEvent previous = passes.stream()
            .filter(
                event -> event.stopOrder() == visit.stopOrder() && event.confirmedAt().isBefore(visit.confirmedAt()))
            .filter(event -> event.departure().receivedAt().atZone(SEOUL).toLocalDate().equals(date))
            .max(Comparator.comparing(LesPassEvent::confirmedAt)).orElse(null);
        if (previous == null) {
            return unsupported("NO_PREVIOUS_PASS");
        }
        final double gap = Duration.between(previous.departure().receivedAt(), departure).toNanos() / 60e9;
        if (gap < .2 || gap > 60) {
            return unsupported("UNSUPPORTED_HEADWAY");
        }
        JsonNode parameters = null;
        for (JsonNode parameter : snapshot.path("alighting").path("stops")) {
            if (parameter.path("stop").asInt() == visit.stopOrder() && parameter.path("band").asText().equals(band)) {
                parameters = parameter;
            }
        }
        if (parameters == null) {
            return unsupported("NO_FITTED_STOP_PARAMETERS");
        }
        LesObservation capacity = capacity(source, visit, snapshot);
        if (capacity == null || capacity.seats() < 30) {
            return unsupported("CAPACITY_EVIDENCE_MISMATCH");
        }
        double[] lambda = new double[8];
        if (parameters.path("lam").size() != lambda.length) {
            return unsupported("INVALID_PARAMETER_DIMENSION");
        }
        for (int i = 0; i < lambda.length; i++) {
            lambda[i] = parameters.path("lam").get(i).asDouble();
        }
        LesAlightingPosterior.Parameters fitted = new LesAlightingPosterior.Parameters(lambda,
            parameters.path("p").asDouble(), parameters.path("kappa").asDouble(), parameters.path("r").asDouble());
        int start = 360;
        if (band.equals("evening")) {
            start = 1020;
        }
        final double label = LesAlightingPosterior.afterSeats(fitted, visit.arrival().seats(),
            visit.departure().seats(),
            capacity.seats(), gap, previous.departure().seats() == 0, (minute - start) / 30);
        return new JdbcLesForecastEvaluation.Outcome("SETTLED", label, null, visit, previous, capacity,
            digest(parameters.toString()), capacity.seats());
    }

    private LesObservation capacity(LesObservation source, LesPassEvent event, JsonNode snapshot) {
        final long id = snapshot.path("capacityObservationId").asLong();
        LesObservation witness = jdbc.sql("""
            SELECT o.*,b.input_confirmed_at,b.response_received_at FROM vehicle_observation o
            JOIN observation_batch b ON b.id=o.observation_batch_id
            WHERE o.id=:id AND o.vehicle_id=:vehicle AND o.seat_unknown_reason IS NULL
              AND b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS'
              AND b.input_confirmed_at>=b.response_received_at
              AND b.input_confirmed_at<=b.response_received_at+interval '90 seconds'
              AND b.input_confirmed_at<=:source
              AND o.route_version_id IN (SELECT id FROM route_version WHERE route_id=
                  (SELECT route_id FROM route_version WHERE id=:route))
            """).param("id", id).param("vehicle", source.vehicleId()).param("source", offset(source.receivedAt()))
            .param("route", route).query((rs, i) -> new LesObservation(id, rs.getLong("observation_batch_id"), 0,
                rs.getObject("input_confirmed_at", OffsetDateTime.class).toInstant(), source.vehicleId(),
                rs.getInt("passed_stop_order"), rs.getInt("stop_order"), rs.getInt("running_state"),
                rs.getObject("remaining_seats", Integer.class), rs.getInt("crowd_level"),
                rs.getLong("route_version_id"), rs.getObject("response_received_at", OffsetDateTime.class).toInstant(),
                source.down()))
            .optional().orElse(null);
        if (witness == null || witness.seats() == null || witness.seats() != snapshot.path("capacity").asInt()) {
            return null;
        }
        return Stream.concat(Stream.of(witness), rows.stream())
            .filter(row -> row.vehicleId().equals(source.vehicleId()) && row.seats() != null)
            .filter(row -> row.seats() > 0 && row.seats() <= 70 && !row.receivedAt().isAfter(event.confirmedAt()))
            .max(Comparator.comparingInt(LesObservation::seats).thenComparing(LesObservation::receivedAt)).orElse(null);
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

    private static String band(final int minute) {
        if (minute >= 360 && minute < 600) {
            return "morning";
        }
        if (minute >= 1020 && minute < 1260) {
            return "evening";
        }
        return "";
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

    private static JdbcLesForecastEvaluation.Outcome unsupported(String reason) {
        return JdbcLesForecastEvaluation.Outcome.unsupported(reason);
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
