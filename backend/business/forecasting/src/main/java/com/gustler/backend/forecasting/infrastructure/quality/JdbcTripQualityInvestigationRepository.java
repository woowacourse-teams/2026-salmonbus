package com.gustler.backend.forecasting.infrastructure.quality;

import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.Phase;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTripQualityInvestigationRepository implements TripQualityInvestigationRepository {
    private final JdbcClient jdbc;
    public JdbcTripQualityInvestigationRepository(final JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Key> nextPending() {
        return jdbc.sql("""
            SELECT route_version_id, vehicle_id FROM trip_quality_rebuild
            WHERE NOT completed AND vehicle_id <> '' ORDER BY investigated_at, route_version_id, vehicle_id LIMIT 1
            """).query((rs, n) -> new Key(rs.getLong(1), rs.getString(2))).optional();
    }

    @Override
    public List<String> activeVehicleIds(final long version) {
        return jdbc.sql("SELECT vehicle_id FROM trip_quality_rebuild WHERE route_version_id = ? AND NOT completed")
            .param(version).query(String.class).list();
    }

    @Override
    public void saveStart(final TripQualityInvestigation investigation) {
        jdbc.sql("""
            INSERT INTO trip_quality_rebuild(route_version_id, vehicle_id, last_batch_at, last_batch_id,
                until_at, maximum_gap_seconds, completed, phase, evidence_observation_id, anchor_observation_id,
                previous_observation_id, boundary_candidate_observation_id, include_cursor, can_release)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(route_version_id, vehicle_id) DO UPDATE SET
                last_batch_at = EXCLUDED.last_batch_at, last_batch_id = EXCLUDED.last_batch_id,
                until_at = EXCLUDED.until_at, maximum_gap_seconds = EXCLUDED.maximum_gap_seconds,
                completed = EXCLUDED.completed, phase = EXCLUDED.phase,
                evidence_observation_id = EXCLUDED.evidence_observation_id,
                anchor_observation_id = EXCLUDED.anchor_observation_id,
                previous_observation_id = EXCLUDED.previous_observation_id,
                boundary_candidate_observation_id = EXCLUDED.boundary_candidate_observation_id,
                include_cursor = EXCLUDED.include_cursor, can_release = EXCLUDED.can_release,
                investigated_at = CURRENT_TIMESTAMP, started_at = CURRENT_TIMESTAMP
            """).param(investigation.routeVersionId()).param(investigation.vehicleId())
            .param(offset(investigation.cursorAt())).param(investigation.cursorBatchId())
            .param(offset(investigation.evidenceAt())).param(investigation.maximumGap().toSeconds())
            .param(investigation.completed()).param(investigation.phase().name())
            .param(investigation.evidenceObservationId()).param(investigation.anchorObservationId())
            .param(investigation.previousObservationId()).param(investigation.boundaryCandidateObservationId())
            .param(investigation.includeCursor()).param(investigation.canRelease()).update();
    }

    @Override
    public Optional<TripQualityInvestigation> findPending(final long version, final String vehicle) {
        return jdbc.sql("""
            SELECT route_version_id, vehicle_id, last_batch_at, last_batch_id, until_at, phase,
                   evidence_observation_id, anchor_observation_id, previous_observation_id,
                   include_cursor, can_release, maximum_gap_seconds, boundary_candidate_observation_id, completed
            FROM trip_quality_rebuild WHERE route_version_id = ? AND vehicle_id = ? AND NOT completed
            """).param(version).param(vehicle).query((rs, n) -> investigation(rs)).optional();
    }

    private static TripQualityInvestigation investigation(final ResultSet rs) throws SQLException {
        final Integer seconds = rs.getObject("maximum_gap_seconds", Integer.class);
        return new TripQualityInvestigation(rs.getLong("route_version_id"), rs.getString("vehicle_id"),
            rs.getObject("last_batch_at", OffsetDateTime.class).toInstant(), rs.getLong("last_batch_id"),
            rs.getObject("until_at", OffsetDateTime.class).toInstant(), rs.getLong("evidence_observation_id"),
            "SEARCH_START".equals(rs.getString("phase")) ? Phase.SEARCH_START : Phase.REPLAY, rs.getLong("anchor_observation_id"),
            rs.getObject("previous_observation_id", Long.class), rs.getBoolean("include_cursor"), rs.getBoolean("can_release"),
            seconds == null ? null : Duration.ofSeconds(seconds), rs.getObject("boundary_candidate_observation_id", Long.class),
            rs.getBoolean("completed"));
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

    private static OffsetDateTime offset(final Instant at) { return at.atOffset(ZoneOffset.UTC); }
}
