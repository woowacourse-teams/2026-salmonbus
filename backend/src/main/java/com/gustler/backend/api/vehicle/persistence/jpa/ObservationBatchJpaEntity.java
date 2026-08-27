package com.gustler.backend.api.vehicle.persistence.jpa;

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

@Entity
@Table(name = "observation_batch")
public class ObservationBatchJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_version_id", nullable = false)
    private RouteVersionJpaEntity routeVersion;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "response_received_at")
    private OffsetDateTime responseReceivedAt;

    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    protected ObservationBatchJpaEntity() {
    }

    public Long id() {
        return id;
    }

    public String outcome() {
        return outcome;
    }

    public OffsetDateTime responseReceivedAt() {
        return responseReceivedAt;
    }

    public OffsetDateTime effectivePollAt() {
        if (responseReceivedAt != null) {
            return responseReceivedAt;
        }
        return requestedAt;
    }
}
