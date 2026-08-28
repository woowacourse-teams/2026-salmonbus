package com.gustler.backend.collector.persistence.jpa;

import com.gustler.backend.collector.ObservationAttempt;
import com.gustler.backend.collector.ObservationBatch;
import com.gustler.backend.collector.ObservationBatchOutcome;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity(name = "CollectorObservationBatch")
@Table(name = "observation_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObservationBatchJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long routeVersionId;

    private OffsetDateTime scheduledAt;

    private Integer attemptNumber;

    private String attemptKey;

    private OffsetDateTime requestedAt;

    private OffsetDateTime responseReceivedAt;

    @Enumerated(EnumType.STRING)
    private ObservationBatchOutcome outcome;

    private Integer providerRows;

    private Integer storedRows;

    private Integer excludedRows;

    private String normalizationVersion;

    public ObservationBatchJpaEntity(
        ObservationBatch batch
    ) {
        ObservationAttempt attempt = batch.attempt();
        this.routeVersionId = attempt.routeVersionId();
        this.scheduledAt = attempt.scheduledAt();
        this.attemptNumber = attempt.attemptNumber();
        this.attemptKey = attempt.attemptKey();
        this.requestedAt = attempt.requestedAt();
        this.responseReceivedAt = attempt.responseReceivedAt();
        this.outcome = batch.outcome();
        this.providerRows = batch.providerRows();
        this.storedRows = batch.storedRows();
        this.excludedRows = batch.excludedRows();
        this.normalizationVersion = batch.normalizationVersion();
    }
}
