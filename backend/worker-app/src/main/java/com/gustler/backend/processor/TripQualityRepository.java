package com.gustler.backend.processor;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 실시간/과거 재판정이 공유하는 저장 경계. 관측별 SQL 대신 묶음 조회와 집합 저장을 한다. */
@Repository
public class TripQualityRepository {
    private final JdbcClient jdbc;

    public TripQualityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> findRecentUnassessedBatches(Instant from) {
        return jdbc.sql("""
            SELECT id FROM (
                SELECT b.id, b.response_received_at,
                       row_number() OVER (PARTITION BY b.route_version_id ORDER BY b.response_received_at, b.id) AS n
                FROM observation_batch b JOIN route_version v ON v.id = b.route_version_id
                WHERE v.valid_to IS NULL AND b.response_received_at >= ? AND b.outcome = 'SUCCESS_ROWS'
                  AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild r
                                  WHERE r.route_version_id = b.route_version_id AND NOT r.completed)
                  AND EXISTS (SELECT 1 FROM vehicle_observation o
                              LEFT JOIN vehicle_one_way_trip t ON t.id = o.vehicle_trip_key
                              WHERE o.observation_batch_id = b.id AND t.id IS NULL)
            ) pending WHERE n <= 20 ORDER BY response_received_at, id
            """).param(OffsetDateTime.ofInstant(from, ZoneOffset.UTC)).query(Long.class).list();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assessBatch(long batchId) {
        // 이미 판정한 묶음은 한 번의 읽기로 끝낸다. 잠금/관측 재저장을 반복하지 않는다.
        var version = jdbc.sql("""
            SELECT b.route_version_id FROM observation_batch b WHERE b.id = ?
              AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild r
                              WHERE r.route_version_id = b.route_version_id AND NOT r.completed)
              AND EXISTS (SELECT 1 FROM vehicle_observation o
                          LEFT JOIN vehicle_one_way_trip t ON t.id = o.vehicle_trip_key
                          WHERE o.observation_batch_id = b.id AND t.id IS NULL)
            """).param(batchId).query(Long.class).optional();
        if (version.isEmpty()) {
            return;
        }
        lockRoute(version.get());
        assessBatchLocked(batchId, version.get(), false);
    }

    /** 호출자는 같은 transaction에서 lockRoute를 먼저 호출해야 한다. */
    public void assessBatchLocked(long batchId, long version, boolean replace) {
        Route route = readRoute(version);
        // 직전 원본부터 고른다. 미판정 원본을 건너뛰어 더 오래된 편도와 연결하지 않는다.
        // 시간순 batch 인덱스와 (batch, vehicle) 인덱스로 직전 관측을 찾는다.
        List<Input> inputs = jdbc.sql("""
            SELECT current.id, current.vehicle_id, current.stop_order, current.running_state, current.remaining_seats,
                   target.response_received_at,
                   previous.id AS previous_id, previous.vehicle_id AS previous_vehicle_id,
                   previous.stop_order AS previous_stop_order, previous.running_state AS previous_running_state,
                   previous.remaining_seats AS previous_remaining_seats, previous.response_received_at AS previous_at,
                   trip.start_observation_id AS trip_id, trip.status,
                   origin.stop_order AS origin_stop, origin.running_state AS origin_state
            FROM observation_batch target
            JOIN vehicle_observation current ON current.observation_batch_id = target.id
            LEFT JOIN vehicle_one_way_trip assessed ON assessed.id = current.vehicle_trip_key
            LEFT JOIN LATERAL (
                SELECT o.id, o.vehicle_id, o.stop_order, o.running_state, o.remaining_seats,
                       o.vehicle_trip_key, b.response_received_at
                FROM observation_batch b
                CROSS JOIN LATERAL (
                    SELECT o.* FROM vehicle_observation o
                    WHERE o.observation_batch_id = b.id AND o.vehicle_id = current.vehicle_id LIMIT 1
                ) o
                WHERE current.vehicle_id IS NOT NULL AND b.route_version_id = :version
                  -- 처음 나타난 차량은 노선 전체의 과거 batch를 역순 탐색하지 않는다.
                  AND EXISTS (SELECT 1 FROM vehicle_one_way_trip seen
                              WHERE seen.route_version_id = :version AND seen.vehicle_id = current.vehicle_id)
                  AND (b.response_received_at, b.id) < (target.response_received_at, target.id)
                ORDER BY b.response_received_at DESC, b.id DESC LIMIT 1
            ) previous ON true
            LEFT JOIN vehicle_one_way_trip trip ON trip.id = previous.vehicle_trip_key
            LEFT JOIN vehicle_observation origin ON origin.id = trip.start_observation_id
            WHERE target.id = :batch AND target.route_version_id = :version
              AND (:replace OR assessed.id IS NULL)
              AND (:replace OR NOT EXISTS (SELECT 1 FROM trip_quality_rebuild r
                   WHERE r.route_version_id = :version AND NOT r.completed))
            ORDER BY current.source_row_number
            """).param("batch", batchId).param("version", version).param("replace", replace)
            .query((rs, n) -> {
                Observation current = new Observation(rs.getLong("id"), rs.getString("vehicle_id"),
                    rs.getObject("response_received_at", OffsetDateTime.class).toInstant(), rs.getInt("stop_order"),
                    rs.getObject("running_state", Integer.class), rs.getObject("remaining_seats", Integer.class));
                return new Input(current, previousOf(rs));
            }).list();
        if (inputs.isEmpty()) {
            return;
        }
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
            int changed = jdbc.sql("""
                UPDATE vehicle_one_way_trip t SET status = 'EXCLUDED', evidence_observation_id = excluded.evidence,
                    assessed_at = CURRENT_TIMESTAMP
                FROM (VALUES
                """ + values(exclusions.size() / 2, "(CAST(? AS varchar), CAST(? AS bigint))") + """
                ) AS excluded(id, evidence) WHERE t.id = excluded.id AND t.status <> 'EXCLUDED'
                """).params(exclusions).update();
            if (changed > 0) {
                invalidateDerivedInputs(version);
            }
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

    private static Previous previousOf(ResultSet rs) throws SQLException {
        Long trip = rs.getObject("trip_id", Long.class);
        if (trip == null) {
            return null;
        }
        return new Previous(new Observation(rs.getLong("previous_id"), rs.getString("previous_vehicle_id"),
            rs.getObject("previous_at", OffsetDateTime.class).toInstant(), rs.getInt("previous_stop_order"),
            rs.getObject("previous_running_state", Integer.class), rs.getObject("previous_remaining_seats", Integer.class)),
            trip, Status.valueOf(rs.getString("status")),
            new Start(rs.getInt("origin_stop"), rs.getObject("origin_state", Integer.class)));
    }

    private static String values(int rows, String tuple) {
        return String.join(", ", Collections.nCopies(rows, tuple));
    }

    private record Input(Observation current, Previous previous) { }
}
