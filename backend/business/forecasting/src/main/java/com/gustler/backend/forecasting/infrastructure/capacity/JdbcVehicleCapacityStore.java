package com.gustler.backend.forecasting.infrastructure.capacity;

import com.gustler.backend.forecasting.domain.model.SeatGrid;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVehicleCapacityStore {
    private final JdbcClient jdbc;

    public JdbcVehicleCapacityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Capacity> findAll() {
        return jdbc.sql("SELECT * FROM vehicle_capacity").query(this::mapCapacity).list();
    }

    public List<Capacity> findByVehicleIds(String sourceId, List<String> vehicleIds) {
        return jdbc.sql("SELECT * FROM vehicle_capacity WHERE source_id = :sourceId AND vehicle_id IN (:vehicleIds)")
            .param("sourceId", sourceId)
            .param("vehicleIds", vehicleIds)
            .query(this::mapCapacity)
            .list();
    }

    /**
     * 초과 관측의 원값과 판정을 보관한다. 통과한 양수만 차량별 최대값을 올린다.
     */
    public BatchAssessment assessBatch(final long batchId) {
        return jdbc.sql("""
            WITH candidates AS (
                SELECT observation.id, route.source_id, observation.vehicle_id, observation.remaining_seats,
                       batch.response_received_at, batch.id AS batch_id, saved.verified_seat_limit,
                       COALESCE(saved.verified_seat_limit, CASE WHEN route.source_id = 'GBIS' THEN
                           CASE observation.vehicle_type WHEN 0 THEN 45 WHEN 1 THEN 40 WHEN 2 THEN 68 WHEN 5 THEN 45 END
                       END) AS seat_limit,
                       COALESCE(saved.capacity, 0) AS saved_capacity
                FROM vehicle_observation observation
                JOIN observation_batch batch ON batch.id = observation.observation_batch_id
                JOIN route_version version ON version.id = observation.route_version_id
                JOIN route ON route.id = version.route_id
                LEFT JOIN vehicle_capacity saved
                  ON saved.source_id = route.source_id AND saved.vehicle_id = observation.vehicle_id
                WHERE batch.id = :batchId AND observation.remaining_seats > 0
            ), decisions AS MATERIALIZED (
                SELECT *, CASE
                    WHEN remaining_seats > verified_seat_limit THEN 'OUTLIER'
                    WHEN remaining_seats > :maximumSeats OR remaining_seats > seat_limit THEN 'REVIEW'
                    WHEN seat_limit IS NULL AND remaining_seats > saved_capacity THEN 'REVIEW'
                END AS seat_quality_status
                FROM candidates
                WHERE remaining_seats > saved_capacity OR remaining_seats > seat_limit
                   OR remaining_seats > :maximumSeats
            ), withheld AS (
                UPDATE vehicle_observation observation
                SET reported_remaining_seats = decisions.remaining_seats,
                    remaining_seats = NULL,
                    seat_unknown_reason = 'REPORTED_UNKNOWN',
                    seat_quality_status = decisions.seat_quality_status
                FROM decisions
                WHERE observation.id = decisions.id AND decisions.seat_quality_status IS NOT NULL
                RETURNING observation.id, observation.seat_quality_status
            ), raised AS (
                INSERT INTO vehicle_capacity (
                    source_id, vehicle_id, capacity, evidence_at, evidence_batch_id, evidence_observation_id
                )
                SELECT DISTINCT ON (source_id, vehicle_id)
                    source_id, vehicle_id, remaining_seats, response_received_at, batch_id, id
                FROM decisions
                WHERE seat_quality_status IS NULL AND remaining_seats > saved_capacity
                  AND vehicle_id IS NOT NULL AND btrim(vehicle_id) <> ''
                ORDER BY source_id, vehicle_id, remaining_seats DESC, id
                ON CONFLICT (source_id, vehicle_id) DO UPDATE SET
                    capacity = EXCLUDED.capacity,
                    evidence_at = EXCLUDED.evidence_at,
                    evidence_batch_id = EXCLUDED.evidence_batch_id,
                    evidence_observation_id = EXCLUDED.evidence_observation_id,
                    updated_at = now()
                WHERE EXCLUDED.capacity > vehicle_capacity.capacity
                  AND (vehicle_capacity.verified_seat_limit IS NULL
                       OR EXCLUDED.capacity <= vehicle_capacity.verified_seat_limit)
                RETURNING source_id, vehicle_id, capacity, evidence_at, evidence_batch_id
            )
            SELECT NULL::bigint AS withheld_id, false AS outlier, raised.* FROM raised
            UNION ALL
            SELECT id, seat_quality_status = 'OUTLIER', NULL, NULL, NULL, NULL, NULL FROM withheld
            """)
            .param("batchId", batchId)
            .param("maximumSeats", SeatGrid.LARGEST_SEATS)
            .query(this::mapAssessment);
    }

    private BatchAssessment mapAssessment(ResultSet resultSet) throws SQLException {
        List<Capacity> raised = new ArrayList<>();
        Set<Long> withheldIds = new HashSet<>();
        int outliers = 0;
        while (resultSet.next()) {
            Long withheldId = resultSet.getObject("withheld_id", Long.class);
            if (withheldId == null) {
                raised.add(mapCapacity(resultSet, 0));
                continue;
            }
            withheldIds.add(withheldId);
            if (resultSet.getBoolean("outlier")) {
                outliers++;
            }
        }
        return new BatchAssessment(List.copyOf(raised), Set.copyOf(withheldIds), outliers);
    }

    public record BatchAssessment(List<Capacity> raised, Set<Long> withheldObservationIds, int outliers) {
    }

    private Capacity mapCapacity(ResultSet resultSet, final int rowNumber) throws SQLException {
        return new Capacity(
            resultSet.getString("source_id"),
            resultSet.getString("vehicle_id"),
            resultSet.getInt("capacity"),
            resultSet.getObject("evidence_at", OffsetDateTime.class).toInstant(),
            resultSet.getObject("evidence_batch_id", Long.class));
    }

    public record Capacity(String sourceId, String vehicleId, int seats, Instant evidenceAt, Long evidenceBatchId) {
        public boolean canUseInStatistics(Instant dataUntil) {
            return evidenceBatchId != null || !evidenceAt.isAfter(dataUntil);
        }

        public boolean wasKnownAt(Instant until, final long batchId) {
            if (!evidenceAt.equals(until)) {
                return evidenceAt.isBefore(until);
            }
            return evidenceBatchId == null || evidenceBatchId <= batchId;
        }
    }
}
