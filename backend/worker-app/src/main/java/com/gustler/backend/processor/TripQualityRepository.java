package com.gustler.backend.processor;

import com.gustler.backend.observation.VehicleObservationsStored;
import com.gustler.backend.processor.OneWayTripClassifier.Boundary;
import com.gustler.backend.processor.OneWayTripClassifier.Decision;
import com.gustler.backend.processor.OneWayTripClassifier.Observation;
import com.gustler.backend.processor.OneWayTripClassifier.Previous;
import com.gustler.backend.processor.OneWayTripClassifier.Route;
import com.gustler.backend.processor.OneWayTripClassifier.Start;
import com.gustler.backend.processor.OneWayTripClassifier.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TripQualityRepository {
    public static final int INVESTIGATION_BATCH_LIMIT = 32;
    private final JdbcClient jdbc;

    public TripQualityRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void observationsStored(VehicleObservationsStored event) {
        var anomalies = event.rows().stream()
            .filter(row -> row.vehicleId() != null && !row.vehicleId().isBlank()
                && row.remainingSeats() != null && row.remainingSeats() > 70).toList();
        if (anomalies.isEmpty()) {
            return;
        }
        lockRoute(event.routeVersionId());
        var active = jdbc.sql("SELECT vehicle_id FROM trip_quality_rebuild WHERE route_version_id = ? AND NOT completed")
            .param(event.routeVersionId()).query(String.class).list();
        boolean changed = false;
        for (var row : anomalies) {
            if (active.contains(row.vehicleId())) { continue; }
            jdbc.sql("""
                INSERT INTO trip_quality_rebuild(route_version_id, vehicle_id, last_batch_at, last_batch_id,
                    until_at, maximum_gap_seconds, completed, phase, evidence_observation_id, anchor_observation_id)
                SELECT ?, ?, ?, ?, ?, COALESCE(maximum_observation_gap_seconds, ?), false, 'SEARCH_START', ?, ?
                FROM route_version WHERE id = ?
                ON CONFLICT(route_version_id, vehicle_id) DO UPDATE SET
                    last_batch_at = EXCLUDED.last_batch_at, last_batch_id = EXCLUDED.last_batch_id,
                    until_at = EXCLUDED.until_at, maximum_gap_seconds = EXCLUDED.maximum_gap_seconds,
                    completed = false, phase = 'SEARCH_START', evidence_observation_id = EXCLUDED.evidence_observation_id,
                    anchor_observation_id = EXCLUDED.anchor_observation_id, previous_observation_id = NULL,
                    include_cursor = false, can_release = false, investigated_at = CURRENT_TIMESTAMP,
                    started_at = CURRENT_TIMESTAMP
                """).param(event.routeVersionId()).param(row.vehicleId()).param(offset(event.observedAt()))
                .param(event.batchId()).param(offset(event.observedAt()))
                .param(OneWayTripClassifier.DEFAULT_MAXIMUM_GAP.toSeconds()).param(row.observationId())
                .param(row.observationId()).param(event.routeVersionId()).update();
            changed = true;
        }
        if (changed) { invalidateDerivedInputs(event.routeVersionId()); }
    }

    @Transactional(timeout = 2)
    public boolean investigateNext() {
        var keys = jdbc.sql("""
            SELECT route_version_id, vehicle_id FROM trip_quality_rebuild
            WHERE NOT completed AND vehicle_id <> '' ORDER BY investigated_at, route_version_id, vehicle_id LIMIT 1
            """).query((rs, n) -> new Key(rs.getLong(1), rs.getString(2))).list();
        if (keys.isEmpty()) { return false; }
        jdbc.sql("SELECT set_config('statement_timeout', '500ms', true), set_config('lock_timeout', '100ms', true)")
            .query().singleRow();
        investigateLocked(keys.getFirst().version(), keys.getFirst().vehicle());
        return true;
    }

    public void investigateLocked(long version, String vehicle) {
        lockRoute(version);
        var jobs = jdbc.sql("""
            SELECT last_batch_at, last_batch_id, until_at, phase, anchor_observation_id,
                   previous_observation_id, include_cursor, can_release, maximum_gap_seconds
            FROM trip_quality_rebuild WHERE route_version_id = ? AND vehicle_id = ? AND NOT completed
            """).param(version).param(vehicle).query((rs, n) -> new Job(
                rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getLong(2),
                rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getString(4), rs.getLong(5),
                rs.getObject(6, Long.class), rs.getBoolean(7), rs.getBoolean(8), rs.getObject(9, Integer.class))).list();
        if (jobs.isEmpty()) { return; }
        Job job = jobs.getFirst();
        Route configured = readRoute(version);
        Route route = new Route(configured.firstStop(), configured.lastStop(), configured.turnStop(),
            job.gap() == null ? null : Duration.ofSeconds(job.gap()));
        boolean backwards = job.phase().equals("SEARCH_START");
        List<ScanRow> rows = readPage(version, vehicle, job.at(), job.batch(), backwards, job.include());
        if (backwards) {
            ScanRow anchor = observation(job.anchor());
            boolean found = false;
            for (ScanRow row : rows) {
                if (row.observation() == null) { continue; }
                Decision decision = OneWayTripClassifier.classify(route,
                    new Previous(row.observation(), row.observation().id(), Status.ELIGIBLE), anchor.observation());
                if (decision.boundary() == Boundary.DEPARTURE || decision.boundary() == Boundary.DIRECTION_CHANGE
                    || decision.boundary() == Boundary.UNCONFIRMED) {
                    found = true;
                    break;
                }
                anchor = row;
            }
            if (found || rows.size() < INVESTIGATION_BATCH_LIMIT) {
                jdbc.sql("""
                    UPDATE trip_quality_rebuild SET phase = 'REPLAY', last_batch_at = ?, last_batch_id = ?,
                        anchor_observation_id = ?, include_cursor = true, investigated_at = CURRENT_TIMESTAMP
                    WHERE route_version_id = ? AND vehicle_id = ?
                    """).param(offset(anchor.at())).param(anchor.batch()).param(anchor.observation().id())
                    .param(version).param(vehicle).update();
            } else {
                ScanRow cursor = rows.getLast();
                jdbc.sql("""
                    UPDATE trip_quality_rebuild SET last_batch_at = ?, last_batch_id = ?, anchor_observation_id = ?,
                        investigated_at = CURRENT_TIMESTAMP WHERE route_version_id = ? AND vehicle_id = ?
                    """).param(offset(cursor.at())).param(cursor.batch()).param(anchor.observation().id())
                    .param(version).param(vehicle).update();
            }
            return;
        }
        Previous previous = job.previous() == null ? null : previous(job.previous());
        List<Input> inputs = new ArrayList<>();
        boolean canRelease = job.canRelease();
        for (ScanRow row : rows) {
            if (row.observation() == null) { continue; }
            Observation current = row.observation();
            Decision decision = OneWayTripClassifier.classify(route, previous, current);
            inputs.add(new Input(current, previous));
            Start start = previous != null && previous.tripId() == decision.tripId()
                ? previous.start() : new Start(current.stopOrder(), current.runningState());
            previous = new Previous(current, decision.tripId(), decision.status(), start);
            canRelease = current.at().isAfter(job.evidenceAt()) && decision.status() == Status.ELIGIBLE;
        }
        persist(inputs, route, version);
        boolean complete = rows.size() < INVESTIGATION_BATCH_LIMIT && canRelease;
        ScanRow cursor = rows.isEmpty() ? null : rows.getLast();
        jdbc.sql("""
            UPDATE trip_quality_rebuild SET last_batch_at = ?, last_batch_id = ?, previous_observation_id = ?,
                include_cursor = false, can_release = ?, completed = ?, phase = ?, investigated_at = CURRENT_TIMESTAMP
            WHERE route_version_id = ? AND vehicle_id = ?
            """).param(offset(cursor == null ? job.at() : cursor.at())).param(cursor == null ? job.batch() : cursor.batch())
            .param(previous == null ? null : previous.observation().id()).param(canRelease).param(complete)
            .param(complete ? "DONE" : "REPLAY").param(version).param(vehicle).update();
        if (complete) { invalidateDerivedInputs(version); }
    }

    public List<ScanRow> readPage(long version, String vehicle, Instant at, long batch, boolean backwards, boolean include) {
        String comparison = backwards ? "<" : include ? ">=" : ">";
        String order = backwards ? "DESC" : "ASC";
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
            .param("limit", INVESTIGATION_BATCH_LIMIT).query((rs, n) -> scanRow(rs)).list();
    }

    private ScanRow observation(long id) {
        return jdbc.sql("""
            SELECT b.id AS batch_id, b.response_received_at, o.id, o.vehicle_id, o.stop_order, o.running_state, o.remaining_seats
            FROM vehicle_observation o JOIN observation_batch b ON b.id = o.observation_batch_id WHERE o.id = ?
            """).param(id).query((rs, n) -> scanRow(rs)).single();
    }

    private Previous previous(long id) {
        Observation observation = observation(id).observation();
        return jdbc.sql("""
            SELECT t.start_observation_id, t.status, origin.stop_order, origin.running_state
            FROM vehicle_observation o JOIN vehicle_one_way_trip t ON t.id = o.vehicle_trip_key
            JOIN vehicle_observation origin ON origin.id = t.start_observation_id WHERE o.id = ?
            """).param(id).query((rs, n) -> new Previous(observation, rs.getLong(1), Status.valueOf(rs.getString(2)),
                new Start(rs.getInt(3), rs.getObject(4, Integer.class)))).single();
    }

    private void persist(List<Input> inputs, Route route, long version) {
        if (inputs.isEmpty()) { return; }
        List<Object> starts = new ArrayList<>();
        List<Object> exclusions = new ArrayList<>();
        List<Object> links = new ArrayList<>();
        for (Input input : inputs) {
            Observation current = input.current();
            Decision decision = OneWayTripClassifier.classify(route, input.previous(), current);
            if (decision.tripId() == current.id()) {
                Collections.addAll(starts, Long.toString(current.id()), current.id(), version, current.vehicleId(),
                    decision.status().name(), decision.boundary().name(), OneWayTripClassifier.RULE_VERSION,
                    decision.status() == Status.EXCLUDED ? current.id() : null);
            } else if (decision.status() == Status.EXCLUDED && input.previous().status() != Status.EXCLUDED) {
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
        jdbc.sql("""
            UPDATE vehicle_observation o SET vehicle_trip_key = assigned.trip
            FROM (VALUES
            """ + values(links.size() / 2, "(CAST(? AS bigint), CAST(? AS varchar))") + """
            ) AS assigned(id, trip) WHERE o.id = assigned.id AND o.vehicle_trip_key IS DISTINCT FROM assigned.trip
            """).params(links).update();
    }
    public Route readRoute(long version) {
        return jdbc.sql("""
            SELECT min(s.stop_order) first_stop, max(s.stop_order) last_stop, v.turn_sequence,
                   v.maximum_observation_gap_seconds
            FROM route_version v JOIN route_stop s ON s.route_version_id = v.id
            WHERE v.id = ? GROUP BY v.turn_sequence, v.maximum_observation_gap_seconds
            """).param(version).query((rs, n) -> {
                Integer gap = rs.getObject("maximum_observation_gap_seconds", Integer.class);
                return new Route(rs.getInt("first_stop"), rs.getInt("last_stop"),
                    rs.getObject("turn_sequence", Integer.class), gap == null ? null : Duration.ofSeconds(gap));
            }).single();
    }

    public void lockRoute(long version) {
        jdbc.sql("SELECT id FROM route WHERE id = (SELECT route_id FROM route_version WHERE id = ?) FOR UPDATE")
            .param(version).query(Long.class).single();
    }

    public void invalidateDerivedInputs(long version) {
        jdbc.sql("UPDATE route SET quality_revision = quality_revision + 1 WHERE id = (SELECT route_id FROM route_version WHERE id = ?)")
            .param(version).update();
    }

    private static ScanRow scanRow(ResultSet rs) throws SQLException {
        Instant at = rs.getObject("response_received_at", OffsetDateTime.class).toInstant();
        Long id = rs.getObject("id", Long.class);
        return new ScanRow(rs.getLong("batch_id"), at, id == null ? null : new Observation(id,
            rs.getString("vehicle_id"), at, rs.getInt("stop_order"), rs.getObject("running_state", Integer.class),
            rs.getObject("remaining_seats", Integer.class)));
    }
    private static OffsetDateTime offset(Instant at) { return at.atOffset(ZoneOffset.UTC); }
    private static String values(int rows, String tuple) { return String.join(", ", Collections.nCopies(rows, tuple)); }
    private record Input(Observation current, Previous previous) { }
    private record Key(long version, String vehicle) { }
    private record Job(Instant at, long batch, Instant evidenceAt, String phase, long anchor, Long previous,
                       boolean include, boolean canRelease, Integer gap) { }
    public record ScanRow(long batch, Instant at, Observation observation) { }
}
