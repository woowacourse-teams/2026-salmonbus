package com.gustler.backend.forecasting.infrastructure.quality;

import com.gustler.backend.forecasting.application.quality.TripQualityStore;
import com.gustler.backend.forecasting.domain.quality.OneWayTripAssessment;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Observation;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Previous;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Start;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Status;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.BatchObservations;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.Phase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTripQualityStore implements TripQualityStore {
    private final JdbcClient jdbc;
    public JdbcTripQualityStore(final JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Key> nextPending() {
        return jdbc.sql("""
            SELECT route_version_id, vehicle_id FROM trip_quality_rebuild
            WHERE NOT completed AND vehicle_id <> '' ORDER BY investigated_at, route_version_id, vehicle_id LIMIT 1
            """).query((rs, n) -> new Key(rs.getLong(1), rs.getString(2))).optional();
    }

    @Override
    public List<Long> registerAnomalies(final QualityObservationBatch batch) {
        final var active = new HashSet<>(jdbc.sql("SELECT vehicle_id FROM trip_quality_rebuild WHERE route_version_id = ? AND NOT completed")
            .param(batch.routeVersionId()).query(String.class).list());
        final List<Long> evidenceIds = new ArrayList<>();
        for (final var row : batch.anomalies()) {
            if (!active.add(row.vehicleId())) { continue; }
            jdbc.sql("""
                INSERT INTO trip_quality_rebuild(route_version_id, vehicle_id, last_batch_at, last_batch_id,
                    until_at, maximum_gap_seconds, completed, phase, evidence_observation_id, anchor_observation_id)
                SELECT v.id, ?, ?, ?, ?, COALESCE(p.maximum_observation_gap_seconds, ?), false, 'SEARCH_START', ?, ?
                FROM route_version v LEFT JOIN route_version_quality_policy p ON p.route_version_id = v.id WHERE v.id = ?
                ON CONFLICT(route_version_id, vehicle_id) DO UPDATE SET
                    last_batch_at = EXCLUDED.last_batch_at, last_batch_id = EXCLUDED.last_batch_id,
                    until_at = EXCLUDED.until_at, maximum_gap_seconds = EXCLUDED.maximum_gap_seconds,
                    completed = false, phase = 'SEARCH_START', evidence_observation_id = EXCLUDED.evidence_observation_id,
                    anchor_observation_id = EXCLUDED.anchor_observation_id, previous_observation_id = NULL,
                    boundary_candidate_observation_id = NULL,
                    include_cursor = false, can_release = false, investigated_at = CURRENT_TIMESTAMP,
                    started_at = CURRENT_TIMESTAMP
                """).param(row.vehicleId()).param(offset(batch.observedAt())).param(batch.batchId())
                .param(offset(batch.observedAt())).param(OneWayTripClassifier.DEFAULT_MAXIMUM_GAP.toSeconds())
                .param(row.observationId()).param(row.observationId()).param(batch.routeVersionId()).update();
            evidenceIds.add(row.observationId());
        }
        return List.copyOf(evidenceIds);
    }

    @Override
    public Optional<TripQualityInvestigation> findPending(final long version, final String vehicle) {
        return jdbc.sql("""
            SELECT last_batch_at, last_batch_id, until_at, phase, anchor_observation_id,
                   previous_observation_id, include_cursor, can_release, maximum_gap_seconds, boundary_candidate_observation_id
            FROM trip_quality_rebuild WHERE route_version_id = ? AND vehicle_id = ? AND NOT completed
            """).param(version).param(vehicle).query((rs, n) -> {
                final Integer seconds = rs.getObject(9, Integer.class);
                return new TripQualityInvestigation(version, vehicle, rs.getObject(1, OffsetDateTime.class).toInstant(),
                    rs.getLong(2), rs.getObject(3, OffsetDateTime.class).toInstant(), Phase.valueOf(rs.getString(4)), rs.getLong(5),
                    rs.getObject(6, Long.class), rs.getBoolean(7), rs.getBoolean(8),
                    seconds == null ? null : Duration.ofSeconds(seconds), rs.getObject(10, Long.class));
            }).optional();
    }

    @Override
    public void save(final TripQualityInvestigation investigation) {
        jdbc.sql("""
            UPDATE trip_quality_rebuild SET phase = ?, last_batch_at = ?, last_batch_id = ?,
                anchor_observation_id = ?, boundary_candidate_observation_id = ?, previous_observation_id = ?,
                include_cursor = ?, can_release = ?, completed = ?, investigated_at = CURRENT_TIMESTAMP
            WHERE route_version_id = ? AND vehicle_id = ?
            """).param(investigation.phase().name()).param(offset(investigation.cursorAt())).param(investigation.cursorBatchId())
            .param(investigation.anchorObservationId()).param(investigation.boundaryCandidateObservationId())
            .param(investigation.previousObservationId()).param(investigation.includeCursor()).param(investigation.canRelease())
            .param(investigation.completed()).param(investigation.routeVersionId()).param(investigation.vehicleId()).update();
    }

    /** 차량이 없는 기간도 제한된 배치 수에서 멈추도록 배치부터 조회한다. */
    @Override
    public List<BatchObservations> readPage(final long version, final String vehicle, final Instant at,
        final long batch, final boolean backwards, final boolean include) {
        final String comparison = backwards ? "<" : include ? ">=" : ">";
        final String order = backwards ? "DESC" : "ASC";
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
    public Map<Long, BatchObservations> observations(final long anchor, final Long candidate) {
        final var ids = Stream.of(anchor, candidate).filter(Objects::nonNull).distinct().toList();
        return jdbc.sql("""
            SELECT b.id AS batch_id, b.response_received_at, o.id, o.vehicle_id, o.stop_order, o.running_state, o.remaining_seats
            FROM vehicle_observation o JOIN observation_batch b ON b.id = o.observation_batch_id WHERE o.id IN (:ids)
            """).param("ids", ids).query((rs, n) -> scanRow(rs)).list().stream()
            .collect(Collectors.toMap(row -> row.observation().id(), row -> row));
    }

    @Override
    public Previous previous(final long id) {
        final Observation observation = observations(id, null).get(id).observation();
        return jdbc.sql("""
            SELECT t.start_observation_id, t.status, origin.stop_order, origin.running_state
            FROM observation_trip_assignment a JOIN vehicle_one_way_trip t ON t.id = a.trip_id
            JOIN vehicle_observation origin ON origin.id = t.start_observation_id WHERE a.observation_id = ?
            """).param(id).query((rs, n) -> new Previous(observation, rs.getLong(1), Status.valueOf(rs.getString(2)),
                new Start(rs.getInt(3), rs.getObject(4, Integer.class)))).single();
    }

    @Override
    public void saveAssessments(final long version, final List<OneWayTripAssessment> assessments) {
        if (assessments.isEmpty()) { return; }
        final List<Object> starts = new ArrayList<>();
        final List<Object> exclusions = new ArrayList<>();
        final List<Object> links = new ArrayList<>();
        for (final OneWayTripAssessment assessment : assessments) {
            final var current = assessment.observation();
            final var decision = assessment.decision();
            if (assessment.startsTrip()) {
                Collections.addAll(starts, Long.toString(current.id()), current.id(), version, current.vehicleId(),
                    decision.status().name(), decision.boundary().name(), OneWayTripClassifier.RULE_VERSION,
                    decision.status() == Status.EXCLUDED ? current.id() : null);
            } else if (assessment.excludesExistingTrip()) {
                Collections.addAll(exclusions, Long.toString(decision.tripId()), current.id());
            }
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
        jdbc.sql("INSERT INTO observation_trip_assignment(observation_id, trip_id) VALUES "
            + values(links.size() / 2, "(CAST(? AS bigint), CAST(? AS varchar))")
            + " ON CONFLICT(observation_id) DO UPDATE SET trip_id = EXCLUDED.trip_id"
            + " WHERE observation_trip_assignment.trip_id IS DISTINCT FROM EXCLUDED.trip_id")
            .params(links).update();
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
                final Integer gap = rs.getObject("maximum_observation_gap_seconds", Integer.class);
                return new Route(rs.getInt("first_stop"), rs.getInt("last_stop"),
                    rs.getObject("turn_sequence", Integer.class), gap == null ? null : Duration.ofSeconds(gap));
            }).single();
    }

    @Override
    public void applyTimeBudget() {
        jdbc.sql("SELECT set_config('statement_timeout', '500ms', true), set_config('lock_timeout', '100ms', true)")
            .query().singleRow();
    }

    private static BatchObservations scanRow(final ResultSet rs) throws SQLException {
        final Instant at = rs.getObject("response_received_at", OffsetDateTime.class).toInstant();
        final Long id = rs.getObject("id", Long.class);
        return new BatchObservations(rs.getLong("batch_id"), at, id == null ? null : new Observation(id,
            rs.getString("vehicle_id"), at, rs.getInt("stop_order"), rs.getObject("running_state", Integer.class),
            rs.getObject("remaining_seats", Integer.class)));
    }
    private static OffsetDateTime offset(final Instant at) { return at.atOffset(ZoneOffset.UTC); }
    private static String values(final int rows, final String tuple) { return String.join(", ", Collections.nCopies(rows, tuple)); }
}
