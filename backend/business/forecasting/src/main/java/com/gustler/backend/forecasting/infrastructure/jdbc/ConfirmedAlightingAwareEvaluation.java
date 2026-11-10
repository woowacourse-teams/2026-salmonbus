package com.gustler.backend.forecasting.infrastructure.jdbc;

import static com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareIdentifiers.canonical;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingPosterior;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvents;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** 확인 시각으로 닫힌 방문과 예보 당시의 차량 정원 근거를 새 정답 판본으로 연결한다. */
final class ConfirmedAlightingAwareEvaluation {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;
    private final long route;
    private final LocalDate date;
    private final Instant through;
    private final int limit;
    private final Map<Long, Long> chains = new HashMap<>();
    private final Map<List<Integer>, List<AlightingAwarePassEvent>> events = new HashMap<>();
    private List<AlightingAwareObservation> rows;
    private Boolean incomplete;

    ConfirmedAlightingAwareEvaluation(
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

    JdbcAlightingAwareForecastEvaluation.Outcome evaluate(final long sourceId, final int target, JsonNode snapshot) {
        if (!canonical(snapshot.path("inputProfile").asText()).equals(AlightingAwareRouteStatistics.CONFIRMED_PROFILE)) {
            return unsupported("INPUT_PROFILE_MISMATCH");
        }
        if (!snapshot.path("capacityPolicy").asText().equals("vehicle-capacity-snapshot-v1")) {
            return unsupported("CAPACITY_POLICY_MISMATCH");
        }
        load(snapshot.path("turn").asInt());
        if (rows.size() > limit) {
            return unsupported("OBSERVATION_INPUT_LIMIT");
        }
        if (incomplete()) {
            return unsupported("SOURCE_HISTORY_INCOMPLETE");
        }
        AlightingAwareObservation source = rows.stream().filter(row -> row.observationId() == sourceId).findFirst().orElse(null);
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
                return new AlightingAwareObservation(rs.getLong("id"), rs.getLong("observation_batch_id"), 0,
                    rs.getObject("input_confirmed_at", OffsetDateTime.class).toInstant(), rs.getString("vehicle_id"),
                    rs.getInt("passed_stop_order"), stop, state, seats, rs.getInt("crowd_level"),
                    rs.getLong("route_version_id"),
                    rs.getObject("response_received_at", OffsetDateTime.class).toInstant(),
                    isDown(stop, state, turn));
            }).list();
        AlightingAwareObservation previous = null;
        long chain = 0;
        for (AlightingAwareObservation row : rows) {
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
        final int minute = departure.atZone(SEOUL).getHour() * 60 + departure.atZone(SEOUL).getMinute();
        String band = band(minute);
        if (band.isEmpty() || departure.atZone(SEOUL).getDayOfWeek().getValue() > 5 || isHoliday(snapshot, departure)) {
            return unsupported("OUTSIDE_FITTED_BAND");
        }
        AlightingAwarePassEvent previous = passes.stream()
            .filter(
                event -> event.stopOrder() == visit.stopOrder() && event.confirmedAt().isBefore(visit.confirmedAt()))
            .filter(event -> event.departure().receivedAt().atZone(SEOUL).toLocalDate().equals(date))
            .max(Comparator.comparing(AlightingAwarePassEvent::confirmedAt)).orElse(null);
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
        int capacity = snapshot.path("capacity").asInt();
        if (capacity < 30 || capacity > 70) {
            return unsupported("CAPACITY_EVIDENCE_MISMATCH");
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
        int start = 360;
        if (band.equals("evening")) {
            start = 1020;
        }
        final double label = AlightingPosterior.afterSeats(fitted, visit.arrival().seats(),
            visit.departure().seats(),
            capacity, gap, previous.departure().seats() == 0, (minute - start) / 30);
        return new JdbcAlightingAwareForecastEvaluation.Outcome("SETTLED", label, null, visit, previous, null,
            digest(parameters.toString()), capacity);
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
