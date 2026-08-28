package com.gustler.backend.collector.persistence.jpa;

import com.gustler.backend.collector.ObservationAttempt;
import com.gustler.backend.collector.ObservationBatch;
import com.gustler.backend.collector.ObservationBatchOutcome;
import jakarta.persistence.Column;
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

/**
 * 조회 쪽도 이 표를 매핑한다. 같은 물리 컬럼에 논리명이 둘이면 Hibernate 가
 * DuplicateMappingException 을 내고 앱이 안 뜬다. 그래서 전 필드에 @Column(name) 을 명시한다.
 */
@Entity(name = "CollectorObservationBatch")
@Table(name = "observation_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObservationBatchJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_version_id")
    private Long routeVersionId;

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(name = "attempt_number")
    private Integer attemptNumber;

    @Column(name = "attempt_key")
    private String attemptKey;

    @Column(name = "requested_at")
    private OffsetDateTime requestedAt;

    @Column(name = "response_received_at")
    private OffsetDateTime responseReceivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome")
    private ObservationBatchOutcome outcome;

    @Column(name = "provider_rows")
    private Integer providerRows;

    @Column(name = "stored_rows")
    private Integer storedRows;

    @Column(name = "excluded_rows")
    private Integer excludedRows;

    @Column(name = "normalization_version")
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
