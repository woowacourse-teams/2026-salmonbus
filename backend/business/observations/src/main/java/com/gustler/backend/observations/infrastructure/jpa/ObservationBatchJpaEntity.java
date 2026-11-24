package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.api.CollectionSchedule;
import com.gustler.backend.observations.domain.CollectionBatchStatus;
import com.gustler.backend.observations.domain.CollectionFailureReason;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.domain.ObservationRows;
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

/** 수집 배치의 저장 값과 시도·종료 상태를 함께 관리한다. */
@Entity(name = "ObservationBatch")
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

    @Column(name = "ingestion_origin", length = 40)
    private String ingestionOrigin = "LIVE";

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome")
    private CollectionBatchStatus outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code")
    private CollectionFailureReason failureCode;

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

    ObservationBatchJpaEntity(CollectionPlan plan, final boolean reserved) {
        routeVersionId = plan.routeVersionId();
        scheduledAt = plan.scheduledAt();
        attemptKey = plan.attemptKey();
        attemptNumber = 1;
        normalizationVersion = ObservationRows.CURRENT_NORMALIZATION_VERSION;
        collectionStrategyVersion = CollectionSchedule.CURRENT_STRATEGY_VERSION;
        prepare(reserved);
    }

    /** 다시 열 수 있는지는 저장소가 확인하며, 지난 관측을 지운 뒤 이 메서드를 부른다. */
    void startNextAttempt(final boolean reserved) {
        attemptNumber++;
        prepare(reserved);

        requestedAt = null;
        responseReceivedAt = null;
        resultCode = null;
        providerRows = null;
        storedRows = null;
        excludedRows = null;
    }

    private void prepare(final boolean reserved) {
        if (reserved) {
            outcome = CollectionBatchStatus.DISPATCHING;
            failureCode = null;
            return;
        }
        outcome = CollectionBatchStatus.NOT_RESERVED;
        failureCode = CollectionFailureReason.LOCAL_QUOTA_EXHAUSTED;
    }

    void abandonBeforeSend() {
        outcome = CollectionBatchStatus.ABANDONED_BEFORE_SEND;
    }

    /** 관측이 있으면 INSERT를 마친 뒤 결과와 건수를 함께 반영한다. */
    void conclude(CollectionResult result, OffsetDateTime receivedAt) {
        outcome = result.outcome();
        failureCode = result.failureCode();
        resultCode = result.upstreamResultCode();
        responseReceivedAt = receivedAt;

        if (result.observations().isPresent()) {
            ObservationRows rows = result.observations().orElseThrow();
            providerRows = rows.providerRows();
            storedRows = rows.storableRowCount();
            excludedRows = rows.excludedRowCount();
        }
    }
}
