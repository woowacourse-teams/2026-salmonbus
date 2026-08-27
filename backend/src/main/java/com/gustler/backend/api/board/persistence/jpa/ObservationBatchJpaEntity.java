package com.gustler.backend.api.board.persistence.jpa;

import com.gustler.backend.api.board.application.SnapshotObservation;
import com.gustler.backend.api.route.persistence.jpa.RouteVersionJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Entity(name = "BoardObservationBatchJpaEntity")
@Table(name = "observation_batch")
public class ObservationBatchJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_version_id", nullable = false)
    private RouteVersionJpaEntity routeVersion;

    @Column(name = "response_received_at")
    private OffsetDateTime responseReceivedAt;

    @Column(name = "forecast_completed_at")
    private OffsetDateTime forecastCompletedAt;

    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Column(name = "stored_rows")
    private Integer storedRows;

    protected ObservationBatchJpaEntity() {
    }

    public SnapshotObservation toDomain(final ZoneId zoneId) {
        return new SnapshotObservation(
            id,
            responseReceivedAt.atZoneSameInstant(zoneId).toOffsetDateTime(),
            storedRows
        );
    }
}
