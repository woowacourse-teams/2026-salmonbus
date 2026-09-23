package com.gustler.backend.forecasting.infrastructure.quality;

import com.gustler.backend.forecasting.api.quality.TripQualityPreview;
import com.gustler.backend.forecasting.api.quality.TripQualityStatus;
import com.gustler.backend.forecasting.application.quality.TripQualityMaintenanceStore;
import com.gustler.backend.forecasting.domain.model.SeatGrid;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.forecasting.domain.quality.TripQualityDiscovery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTripQualityMaintenanceStore implements TripQualityMaintenanceStore {
    private final JdbcClient jdbc;
    public JdbcTripQualityMaintenanceStore(final JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public TripQualityPreview preview(final long version, final Instant until) {
        return jdbc.sql("""
            WITH sample AS MATERIALIZED (
                SELECT id FROM observation_batch WHERE route_version_id = ? AND response_received_at <= ?
                ORDER BY response_received_at DESC, id DESC LIMIT 32
            )
            SELECT (SELECT count(*) FROM sample), count(*), count(*) FILTER (WHERE remaining_seats > ?)
            FROM vehicle_observation WHERE observation_batch_id IN (SELECT id FROM sample)
            """).param(version).param(offset(until)).param(SeatGrid.LARGEST_SEATS)
            .query((rs, n) -> new TripQualityPreview("LAST_32_BATCHES_SAMPLE", rs.getLong(1), rs.getLong(2), rs.getLong(3))).single();
    }

    @Override
    public Optional<TripQualityDiscovery> discovery(final long version) {
        return jdbc.sql("""
            SELECT last_batch_at, last_batch_id, until_at, completed, maximum_gap_seconds
            FROM trip_quality_rebuild WHERE route_version_id = ? AND vehicle_id = ''
            """).param(version).query((rs, n) -> {
                final var at = rs.getObject(1, OffsetDateTime.class);
                return new TripQualityDiscovery(at == null ? Instant.EPOCH : at.toInstant(), rs.getLong(2),
                    rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getLong(5), rs.getBoolean(4));
            }).optional();
    }

    @Override
    public void startDiscovery(final long version, final TripQualityDiscovery discovery) {
        jdbc.sql("INSERT INTO trip_quality_rebuild(route_version_id, until_at, maximum_gap_seconds) VALUES (?, ?, ?)")
            .param(version).param(offset(discovery.until())).param(discovery.maximumGapSeconds()).update();
    }

    @Override
    public List<Batch> discoveryPage(final long version, final TripQualityDiscovery discovery, final int limit) {
        return jdbc.sql("""
            SELECT id, response_received_at FROM observation_batch WHERE route_version_id = ?
              AND response_received_at <= ? AND (response_received_at, id) > (?, ?)
            ORDER BY response_received_at, id LIMIT ?
            """).param(version).param(offset(discovery.until())).param(offset(discovery.cursorAt()))
            .param(discovery.cursorBatchId()).param(limit)
            .query((rs, n) -> new Batch(rs.getLong(1), rs.getObject(2, OffsetDateTime.class).toInstant())).list();
    }

    @Override
    public List<Signal> anomalies(final List<Long> batches) {
        if (batches.isEmpty()) { return List.of(); }
        return jdbc.sql("""
            SELECT o.observation_batch_id, o.id, o.vehicle_id, o.remaining_seats
            FROM vehicle_observation o LEFT JOIN observation_trip_assignment a ON a.observation_id = o.id
            LEFT JOIN vehicle_one_way_trip t ON t.id = a.trip_id
            WHERE o.observation_batch_id IN (:batches) AND o.remaining_seats > :maximumSeats
              AND o.vehicle_id IS NOT NULL AND length(trim(o.vehicle_id)) > 0
              AND (t.status IS NULL OR t.status <> 'EXCLUDED')
            ORDER BY o.observation_batch_id, o.source_row_number
            """).param("batches", batches).param("maximumSeats", SeatGrid.LARGEST_SEATS)
            .query((rs, n) -> new Signal(rs.getLong(1), new QualityObservationBatch.Row(rs.getLong(2), rs.getString(3), rs.getInt(4)))).list();
    }

    @Override
    public void saveDiscovery(final long version, final TripQualityDiscovery discovery) {
        jdbc.sql("""
            UPDATE trip_quality_rebuild SET last_batch_at = ?, last_batch_id = ?, completed = ?, phase = ?
            WHERE route_version_id = ? AND vehicle_id = ''
            """).param(offset(discovery.cursorAt())).param(discovery.cursorBatchId()).param(discovery.completed())
            .param(discovery.completed() ? "DONE" : "DISCOVER").param(version).update();
    }

    @Override
    public Optional<String> nextPendingVehicle(final long version) {
        return jdbc.sql("""
            SELECT vehicle_id FROM trip_quality_rebuild WHERE route_version_id = ? AND vehicle_id <> '' AND NOT completed
            ORDER BY investigated_at, vehicle_id LIMIT 1
            """).param(version).query(String.class).optional();
    }

    @Override
    public boolean hasPendingVehicles(final long version) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM trip_quality_rebuild WHERE route_version_id = ? AND vehicle_id <> '' AND NOT completed)")
            .param(version).query(Boolean.class).single();
    }

    @Override
    public List<TripQualityStatus> status(final long version) {
        return jdbc.sql("""
            SELECT vehicle_id, phase, completed, evidence_observation_id, last_batch_at, last_batch_id,
                   can_release, started_at, investigated_at
            FROM trip_quality_rebuild WHERE route_version_id = ? ORDER BY vehicle_id
            """).param(version).query((rs, n) -> {
                final var at = rs.getObject(5, OffsetDateTime.class);
                return new TripQualityStatus(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getObject(4, Long.class),
                    at == null ? null : at.toInstant(), rs.getLong(6), rs.getBoolean(7),
                    rs.getObject(8, OffsetDateTime.class).toInstant(), rs.getObject(9, OffsetDateTime.class).toInstant());
            }).list();
    }
    private static OffsetDateTime offset(final Instant at) { return at.atOffset(ZoneOffset.UTC); }
}
