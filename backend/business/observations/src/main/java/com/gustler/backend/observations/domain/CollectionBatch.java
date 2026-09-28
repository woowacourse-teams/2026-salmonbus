package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/** 한 수집 계획의 현재 시도와 결과, 입력 확정 여부를 관리한다. */
public final class CollectionBatch {
    private final Long id;
    private final long routeVersionId;
    private final OffsetDateTime scheduledAt;
    private final String attemptKey;
    private int attemptNumber;
    private OffsetDateTime requestedAt;
    private OffsetDateTime responseReceivedAt;
    private OffsetDateTime inputConfirmedAt;
    private ObservationBatchOutcome outcome;
    private ObservationBatchFailureCode failureCode;
    private Integer resultCode;
    private Integer providerRows;
    private Integer storedRows;
    private Integer excludedRows;
    private final String normalizationVersion;
    private final String collectionStrategyVersion;

    private CollectionBatch(State state) {
        id = state.id();
        routeVersionId = state.routeVersionId();
        scheduledAt = Objects.requireNonNull(state.scheduledAt());
        attemptKey = Objects.requireNonNull(state.attemptKey());
        attemptNumber = state.attemptNumber();
        requestedAt = state.requestedAt();
        responseReceivedAt = state.responseReceivedAt();
        inputConfirmedAt = state.inputConfirmedAt();
        outcome = Objects.requireNonNull(state.outcome());
        failureCode = state.failureCode();
        resultCode = state.resultCode();
        providerRows = state.providerRows();
        storedRows = state.storedRows();
        excludedRows = state.excludedRows();
        normalizationVersion = state.normalizationVersion();
        collectionStrategyVersion = state.collectionStrategyVersion();
    }

    public static CollectionBatch start(CollectionPlan plan, final boolean reserved) {
        return restore(new State(null, plan.routeVersionId(), plan.scheduledAt(), plan.attemptKey(), 1,
            null, null, null, reserved ? ObservationBatchOutcome.RESERVED : ObservationBatchOutcome.NOT_RESERVED,
            reserved ? null : ObservationBatchFailureCode.LOCAL_QUOTA_EXHAUSTED, null, null, null, null,
            CollectedObservations.CURRENT_NORMALIZATION_VERSION, CollectionSchedule.CURRENT_STRATEGY_VERSION));
    }

    public static CollectionBatch restore(State state) {
        return new CollectionBatch(state);
    }

    public void startAttempt(final boolean reserved) {
        if (inputConfirmedAt != null) {
            throw new InputAlreadyConfirmedException(id, inputConfirmedAt);
        }
        attemptNumber = attemptNumber + 1;
        outcome = reserved ? ObservationBatchOutcome.RESERVED : ObservationBatchOutcome.NOT_RESERVED;
        failureCode = reserved ? null : ObservationBatchFailureCode.LOCAL_QUOTA_EXHAUSTED;
        requestedAt = null;
        responseReceivedAt = null;
        resultCode = null;
        providerRows = null;
        storedRows = null;
        excludedRows = null;
    }

    public void dispatch(OffsetDateTime sentAt) {
        outcome = ObservationBatchOutcome.DISPATCHING;
        requestedAt = sentAt;
    }

    public void abandonBeforeSend() {
        outcome = ObservationBatchOutcome.ABANDONED_BEFORE_SEND;
    }

    public void conclude(ObservationBatchConclusion conclusion, OffsetDateTime receivedAt) {
        outcome = conclusion.outcome();
        failureCode = conclusion.failureCode();
        resultCode = conclusion.upstreamResultCode();
        responseReceivedAt = receivedAt;
    }

    public void countRows(CollectedObservations collected) {
        providerRows = collected.providerRows();
        storedRows = collected.storableRows().size();
        excludedRows = collected.excludedRows().size();
    }

    public void confirmInput(OffsetDateTime confirmedAt) {
        inputConfirmedAt = confirmedAt;
    }

    public Long id() {
        return id;
    }

    public long routeVersionId() {
        return routeVersionId;
    }

    public State state() {
        return new State(id, routeVersionId, scheduledAt, attemptKey, attemptNumber, requestedAt,
            responseReceivedAt, inputConfirmedAt, outcome, failureCode, resultCode, providerRows,
            storedRows, excludedRows, normalizationVersion, collectionStrategyVersion);
    }

    public record State(Long id, long routeVersionId, OffsetDateTime scheduledAt, String attemptKey,
                        int attemptNumber, OffsetDateTime requestedAt, OffsetDateTime responseReceivedAt,
                        OffsetDateTime inputConfirmedAt, ObservationBatchOutcome outcome,
                        ObservationBatchFailureCode failureCode, Integer resultCode, Integer providerRows,
                        Integer storedRows, Integer excludedRows, String normalizationVersion,
                        String collectionStrategyVersion) {
    }
}
