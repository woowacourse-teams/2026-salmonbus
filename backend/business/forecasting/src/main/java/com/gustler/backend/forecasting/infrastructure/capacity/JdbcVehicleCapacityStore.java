package com.gustler.backend.forecasting.infrastructure.capacity;

import com.gustler.backend.forecasting.domain.model.SeatGrid;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcVehicleCapacityStore {
    private final JdbcClient jdbc;

    public JdbcVehicleCapacityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Capacity> findAll() {
        return jdbc.sql("""
            SELECT source_id, vehicle_id, capacity, evidence_at, evidence_batch_id
            FROM vehicle_capacity
            """)
            .query(this::mapCapacity)
            .list();
    }

    public List<Capacity> findByVehicleIds(String sourceId, List<String> vehicleIds) {
        return jdbc.sql("""
            SELECT source_id, vehicle_id, capacity, evidence_at, evidence_batch_id
            FROM vehicle_capacity
            WHERE source_id = :sourceId AND vehicle_id IN (:vehicleIds)
            """)
            .param("sourceId", sourceId)
            .param("vehicleIds", vehicleIds)
            .query(this::mapCapacity)
            .list();
    }

    public List<Capacity> findKnownAt(String sourceId, List<String> vehicleIds, Instant until, final long batchId) {
        if (vehicleIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
            SELECT DISTINCT ON (vehicle_id) source_id, vehicle_id, capacity, evidence_at, evidence_batch_id
            FROM vehicle_capacity_history
            WHERE source_id = :sourceId AND vehicle_id IN (:vehicleIds)
              AND (evidence_at, evidence_batch_id) <= (:until, :batchId)
            ORDER BY vehicle_id, evidence_at DESC, evidence_batch_id DESC
            """)
            .param("sourceId", sourceId).param("vehicleIds", vehicleIds)
            .param("until", until.atOffset(java.time.ZoneOffset.UTC)).param("batchId", batchId)
            .query(this::mapCapacity).list();
    }

    /** 수집 트랜잭션에서 판정, 원값 보존, 정원 갱신을 모두 완료한다. */
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public BatchAssessment assessBatch(final long batchId) {
        List<Observation> observations = readObservations(batchId);
        List<Long> reviews = observationIdsWithStatus(observations, "REVIEW");
        List<Long> outliers = observationIdsWithStatus(observations, "OUTLIER");

        withholdSeats(reviews, "REVIEW");
        withholdSeats(outliers, "OUTLIER");

        List<Long> accepted = observations.stream()
            .filter(Observation::raisesCapacity)
            .map(Observation::id)
            .toList();
        List<Capacity> raised = raiseCapacities(accepted);
        Set<Long> withheld = Stream.concat(reviews.stream(), outliers.stream())
            .collect(Collectors.toUnmodifiableSet());

        return new BatchAssessment(List.copyOf(raised), withheld, outliers.size());
    }

    private List<Observation> readObservations(final long batchId) {
        return jdbc.sql("""
            SELECT observation.id, route.source_id, observation.remaining_seats,
                   observation.vehicle_type, saved.verified_seat_limit, COALESCE(saved.capacity, 0) AS saved_capacity
            FROM vehicle_observation observation
            JOIN observation_batch batch ON batch.id = observation.observation_batch_id
            JOIN route_version version ON version.id = observation.route_version_id
            JOIN route ON route.id = version.route_id
            LEFT JOIN vehicle_capacity saved
              ON saved.source_id = route.source_id AND saved.vehicle_id = observation.vehicle_id
            WHERE batch.id = :batchId AND observation.remaining_seats > 0
            """)
            .param("batchId", batchId)
            .query((row, number) -> new Observation(
                row.getLong("id"), row.getString("source_id"), row.getInt("remaining_seats"),
                row.getObject("vehicle_type", Integer.class),
                row.getObject("verified_seat_limit", Integer.class), row.getInt("saved_capacity")))
            .list();
    }

    private List<Long> observationIdsWithStatus(List<Observation> observations, String status) {
        return observations.stream()
            .filter(observation -> status.equals(observation.qualityStatus()))
            .map(Observation::id)
            .toList();
    }

    private void withholdSeats(List<Long> observationIds, String status) {
        if (observationIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
            UPDATE vehicle_observation
            SET reported_remaining_seats = remaining_seats,
                remaining_seats = NULL,
                seat_unknown_reason = 'REPORTED_UNKNOWN',
                seat_quality_status = :status
            WHERE id IN (:observationIds)
            """)
            .param("status", status)
            .param("observationIds", observationIds)
            .update();
    }

    private List<Capacity> raiseCapacities(List<Long> observationIds) {
        if (observationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
            INSERT INTO vehicle_capacity (
                source_id, vehicle_id, capacity, evidence_at, evidence_batch_id, evidence_observation_id
            )
            SELECT DISTINCT ON (route.source_id, observation.vehicle_id)
                route.source_id, observation.vehicle_id, observation.remaining_seats,
                batch.response_received_at, batch.id, observation.id
            FROM vehicle_observation observation
            JOIN observation_batch batch ON batch.id = observation.observation_batch_id
            JOIN route_version version ON version.id = observation.route_version_id
            JOIN route ON route.id = version.route_id
            WHERE observation.id IN (:observationIds)
              AND observation.vehicle_id IS NOT NULL AND btrim(observation.vehicle_id) <> ''
            ORDER BY route.source_id, observation.vehicle_id, observation.remaining_seats DESC, observation.id
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
            """)
            .param("observationIds", observationIds)
            .query(this::mapCapacity)
            .list();
    }

    private record Observation(
        long id, String sourceId, int seats, Integer vehicleType, Integer verifiedLimit, int savedCapacity
    ) {
        String qualityStatus() {
            if (verifiedLimit != null && seats > verifiedLimit) {
                return "OUTLIER";
            }
            Integer limit = seatLimit();
            if (seats > SeatGrid.LARGEST_SEATS || (limit != null && seats > limit)) {
                return "REVIEW";
            }
            if (limit == null && seats > savedCapacity) {
                return "REVIEW";
            }
            return null;
        }

        private Integer seatLimit() {
            if (verifiedLimit != null) {
                return verifiedLimit;
            }
            if (!"GBIS".equals(sourceId) || vehicleType == null) {
                return null;
            }
            return switch (vehicleType) {
                case 0, 5 -> 45;
                case 1 -> 40;
                case 2 -> 68;
                default -> null;
            };
        }

        boolean raisesCapacity() {
            return qualityStatus() == null && seats > savedCapacity;
        }
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
