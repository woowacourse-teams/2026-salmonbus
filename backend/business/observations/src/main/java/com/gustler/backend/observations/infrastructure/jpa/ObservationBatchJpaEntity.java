package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.ObservationBatchFailureCode;
import com.gustler.backend.observations.domain.ObservationBatchOutcome;
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

/** 수집 배치의 저장 매핑. 상태 전이 규칙은 CollectionBatch가 관리한다. */
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

    @Column(name = "input_confirmed_at")
    private OffsetDateTime inputConfirmedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome")
    private ObservationBatchOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code")
    private ObservationBatchFailureCode failureCode;

    @Column(name = "result_code")
    private Integer resultCode;

    @Column(name = "provider_rows")
    private Integer providerRows;

    @Column(name = "stored_rows")
    private Integer storedRows;

    @Column(name = "excluded_rows")
    private Integer excludedRows;

    @Column(name = "normalization_version")
    private String normalizationVersion;

    @Column(name = "collection_strategy_version")
    private String collectionStrategyVersion;

    public ObservationBatchJpaEntity(CollectionBatch batch) {
        apply(batch);
    }

    public CollectionBatch toDomain() {
        return CollectionBatch.restore(new CollectionBatch.State(id, routeVersionId, scheduledAt,
            attemptKey, attemptNumber, requestedAt, responseReceivedAt, inputConfirmedAt, outcome,
            failureCode, resultCode, providerRows, storedRows, excludedRows, normalizationVersion,
            collectionStrategyVersion));
    }

    public void apply(CollectionBatch batch) {
        CollectionBatch.State state = batch.state();
        routeVersionId = state.routeVersionId();
        scheduledAt = state.scheduledAt();
        attemptKey = state.attemptKey();
        attemptNumber = state.attemptNumber();
        requestedAt = state.requestedAt();
        responseReceivedAt = state.responseReceivedAt();
        inputConfirmedAt = state.inputConfirmedAt();
        outcome = state.outcome();
        failureCode = state.failureCode();
        resultCode = state.resultCode();
        providerRows = state.providerRows();
        storedRows = state.storedRows();
        excludedRows = state.excludedRows();
        normalizationVersion = state.normalizationVersion();
        collectionStrategyVersion = state.collectionStrategyVersion();
    }
}
