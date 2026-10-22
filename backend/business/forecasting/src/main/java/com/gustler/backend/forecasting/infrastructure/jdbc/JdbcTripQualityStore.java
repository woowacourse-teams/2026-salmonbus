package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.quality.TripQualityStore;
import com.gustler.backend.forecasting.domain.quality.OneWayTripAssessment;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Decision;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Observation;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Previous;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Start;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Status;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.BatchObservations;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 제한된 관측 배치와 편도 근거를 읽고 판정·배정 변경을 저장한다.
 * 빈 차량 관측 기간도 배치 한도에 포함한다.
 */
@Repository
public class JdbcTripQualityStore implements TripQualityStore {
    private final JdbcClient jdbc;

    public JdbcTripQualityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Duration> maximumObservationGap(final long version) {
        return jdbc.sql("""
            SELECT p.maximum_observation_gap_seconds
            FROM route_version v LEFT JOIN route_version_quality_policy p ON p.route_version_id = v.id
            WHERE v.id = ?
            """).param(version).query((rs, n) -> Optional.ofNullable(rs.getObject(1, Integer.class))
            .map(seconds -> Duration.ofSeconds(seconds))).single();
    }

    @Override
    public Route readRoute(final long version) {
        return jdbc.sql("""
            SELECT min(s.stop_order) first_stop, max(s.stop_order) last_stop, v.turn_sequence,
                   p.maximum_observation_gap_seconds
            FROM route_version v JOIN route_stop s ON s.route_version_id = v.id
            LEFT JOIN route_version_quality_policy p ON p.route_version_id = v.id
            WHERE v.id = ? GROUP BY v.turn_sequence, p.maximum_observation_gap_seconds
            """).param(version).query((rs, n) -> {
            Integer gap = rs.getObject("maximum_observation_gap_seconds", Integer.class);
            return new Route(rs.getInt("first_stop"), rs.getInt("last_stop"),
                rs.getObject("turn_sequence", Integer.class), gapOf(gap));
        }).single();
    }

    /** 차량이 없는 기간도 제한된 배치 수에서 멈추도록 배치부터 조회한다. */
    @Override
    public List<BatchObservations> readPage(
        final long version,
        String vehicle,
        Instant at,
        final long batch,
        final boolean backwards,
        final boolean include
    ) {
        String comparison = comparisonFor(backwards, include);
        String order = orderFor(backwards);
        return jdbc.sql("""
            WITH page AS MATERIALIZED (
                SELECT id, response_received_at FROM observation_batch
                WHERE route_version_id = :version AND response_received_at IS NOT NULL
                  AND (response_received_at, id) %s (:at, :batch)
                ORDER BY response_received_at %s, id %s LIMIT :limit
            )
            SELECT p.id AS batch_id, p.response_received_at, o.id, o.vehicle_id,
                   o.stop_order, o.running_state, o.remaining_seats
            FROM page p LEFT JOIN vehicle_observation o
              ON o.observation_batch_id = p.id AND o.vehicle_id = :vehicle
            ORDER BY p.response_received_at %s, p.id %s
            """.formatted(comparison, order, order, order, order))
            .param("version", version).param("at", offset(at)).param("batch", batch).param("vehicle", vehicle)
            .param("limit", TripQualityInvestigation.BATCH_LIMIT).query((rs, n) -> scanRow(rs)).list();
    }

    @Override
    public Map<Long, BatchObservations> observations(final long anchor, Long candidate) {
        List<Long> ids = Stream.of(anchor, candidate)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        return jdbc.sql("""
            SELECT b.id AS batch_id, b.response_received_at, o.id, o.vehicle_id, o.stop_order, o.running_state, o.remaining_seats
            FROM vehicle_observation o JOIN observation_batch b ON b.id = o.observation_batch_id WHERE o.id IN (:ids)
            """)
            .param("ids", ids)
            .query((rs, n) -> scanRow(rs))
            .list()
            .stream()
            .collect(Collectors.toMap(row -> row.observation().id(), row -> row));
    }

    @Override
    public Previous previous(final long id) {
        Observation observation = observations(id, null).get(id).observation();
        return jdbc.sql("""
            SELECT t.start_observation_id, t.status, origin.stop_order, origin.running_state
            FROM observation_trip_assignment a JOIN vehicle_one_way_trip t ON t.id = a.trip_id
            JOIN vehicle_observation origin ON origin.id = t.start_observation_id WHERE a.observation_id = ?
            """).param(id).query((rs, n) -> new Previous(observation, rs.getLong(1), Status.valueOf(rs.getString(2)),
            new Start(rs.getInt(3), rs.getObject(4, Integer.class)))).single();
    }

    @Override
    public void saveAssessments(final long version, List<OneWayTripAssessment> assessments) {
        if (assessments.isEmpty()) {
            return;
        }
        List<Object> starts = new ArrayList<>();
        List<Object> exclusions = new ArrayList<>();
        List<Object> links = new ArrayList<>();
        for (OneWayTripAssessment assessment : assessments) {
            Observation current = assessment.observation();
            Decision decision = assessment.decision();
            appendTripChange(assessment, version, starts, exclusions);
            Collections.addAll(links, current.id(), Long.toString(decision.tripId()));
        }
        if (!starts.isEmpty()) {
            jdbc.sql("""
                INSERT INTO vehicle_one_way_trip(id, start_observation_id, route_version_id, vehicle_id,
                    status, boundary, rule_version, evidence_observation_id) VALUES
                """ + values(starts.size() / 8, "(?, ?, ?, ?, ?, ?, ?, ?)") + """
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, boundary = EXCLUDED.boundary,
                    rule_version = EXCLUDED.rule_version, evidence_observation_id = EXCLUDED.evidence_observation_id,
                    assessed_at = CURRENT_TIMESTAMP
                """).params(starts).update();
        }
        if (!exclusions.isEmpty()) {
            jdbc.sql("""
                UPDATE vehicle_one_way_trip t SET status = 'EXCLUDED', evidence_observation_id = excluded.evidence,
                    assessed_at = CURRENT_TIMESTAMP
                FROM (VALUES
                """ + values(exclusions.size() / 2, "(CAST(? AS varchar), CAST(? AS bigint))") + """
                ) AS excluded(id, evidence) WHERE t.id = excluded.id AND t.status <> 'EXCLUDED'
                """).params(exclusions).update();
        }
        jdbc.sql("INSERT INTO observation_trip_assignment(observation_id, trip_id)"
            + " SELECT link.observation_id, link.trip_id FROM (VALUES "
            + values(links.size() / 2, "(CAST(? AS bigint), CAST(? AS varchar))")
            + ") AS link(observation_id, trip_id)"
            + " JOIN vehicle_observation observation ON observation.id = link.observation_id"
            + " ON CONFLICT(observation_id) DO UPDATE SET trip_id = EXCLUDED.trip_id"
            + " WHERE observation_trip_assignment.trip_id IS DISTINCT FROM EXCLUDED.trip_id")
            .params(links).update();
    }

    @Override
    public void applyTimeBudget() {
        jdbc.sql("SELECT set_config('statement_timeout', '500ms', true), set_config('lock_timeout', '100ms', true)")
            .query().singleRow();
    }

    private static BatchObservations scanRow(ResultSet rs) throws SQLException {
        Instant at = rs.getObject("response_received_at", OffsetDateTime.class).toInstant();
        Long id = rs.getObject("id", Long.class);
        return new BatchObservations(rs.getLong("batch_id"), at, observationOf(rs, id, at));
    }

    private static OffsetDateTime offset(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    private static String values(final int rows, String tuple) {
        return String.join(", ", Collections.nCopies(rows, tuple));
    }

    private static String comparisonFor(final boolean backwards, final boolean include) {
        if (backwards) {
            return "<";
        }
        if (include) {
            return ">=";
        }
        return ">";
    }

    private static String orderFor(final boolean backwards) {
        if (backwards) {
            return "DESC";
        }
        return "ASC";
    }

    private static void appendTripChange(
        OneWayTripAssessment assessment,
        final long version,
        List<Object> starts,
        List<Object> exclusions
    ) {
        Observation current = assessment.observation();
        Decision decision = assessment.decision();
        if (assessment.startsTrip()) {
            Collections.addAll(starts, Long.toString(current.id()), current.id(), version, current.vehicleId(),
                decision.status().name(), decision.boundary().name(), OneWayTripClassifier.RULE_VERSION,
                excludedObservationId(decision, current));
            return;
        }
        if (assessment.excludesExistingTrip()) {
            Collections.addAll(exclusions, Long.toString(decision.tripId()), current.id());
        }
    }

    private static Long excludedObservationId(Decision decision, Observation current) {
        if (decision.status() == Status.EXCLUDED) {
            return current.id();
        }
        return null;
    }

    private static Duration gapOf(Integer seconds) {
        if (seconds == null) {
            return null;
        }
        return Duration.ofSeconds(seconds);
    }

    private static Observation observationOf(ResultSet rs, Long id, Instant at) throws SQLException {
        if (id == null) {
            return null;
        }
        return new Observation(id, rs.getString("vehicle_id"), at, rs.getInt("stop_order"),
            rs.getObject("running_state", Integer.class), rs.getObject("remaining_seats", Integer.class));
    }
}
