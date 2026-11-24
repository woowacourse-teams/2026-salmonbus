package com.gustler.backend.observations.domain;

import com.gustler.backend.observations.api.CollectionSchedule;
import java.time.OffsetDateTime;
import java.util.Objects;

/** 한 수집 계획의 현재 시도와 결과를 관리한다. 입력 확인 시각이 적힌 배치는 다시 열지 않는다. */
public final class CollectionBatch {

    private final Long id;
    private final long routeVersionId;
    private final OffsetDateTime scheduledAt;
    private final String attemptKey;
    private final String normalizationVersion;
    private final String collectionStrategyVersion;
    private final OffsetDateTime inputConfirmedAt;

    private int attemptNumber;
    /** 기존 수집 이력을 읽고 재개 시 비운다. 새 수집은 전송시각을 기록하지 않는다. */
    private OffsetDateTime requestedAt;
    private OffsetDateTime responseReceivedAt;
    private CollectionBatchStatus outcome;
    private CollectionFailureReason failureCode;
    private Integer resultCode;
    private Integer providerRows;
    private Integer storedRows;
    private Integer excludedRows;

    private CollectionBatch(CollectionBatch.State state) {
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

    /**
     * 계획된 수집 시각과 시도 키로 아직 전송하지 않은 묶음을 만든다. 예약과 최초 저장의 원자성은 호출 트랜잭션이 보장한다.
     */
    public static CollectionBatch start(CollectionPlan plan, final boolean reserved) {
        return restore(new CollectionBatch.State(
                null,
                plan.routeVersionId(),
                plan.scheduledAt(),
                plan.attemptKey(),
                1,
                null,
                null,
                null,
                preparationOutcome(reserved),
                reservationFailureCode(reserved),
                null,
                null,
                null,
                null,
                ObservationRows.CURRENT_NORMALIZATION_VERSION,
                CollectionSchedule.CURRENT_STRATEGY_VERSION
        ));
    }

    private static CollectionBatchStatus preparationOutcome(final boolean reserved) {
        if (reserved) {
            return CollectionBatchStatus.DISPATCHING;
        }
        return CollectionBatchStatus.NOT_RESERVED;
    }

    private static CollectionFailureReason reservationFailureCode(final boolean reserved) {
        if (reserved) {
            return null;
        }
        return CollectionFailureReason.LOCAL_QUOTA_EXHAUSTED;
    }

    public static CollectionBatch restore(CollectionBatch.State state) {
        return new CollectionBatch(state);
    }

    /**
     * 재시도 번호와 준비 상태를 갱신한다. 이전 응답의 건수·실패 상태가 새 시도에 남지 않게 초기화한다.
     */
    public void startAttempt(final boolean reserved) {
        if (inputConfirmedAt != null) {
            throw new CollectionInputConfirmedException(id, inputConfirmedAt);
        }

        attemptNumber = attemptNumber + 1;
        outcome = preparationOutcome(reserved);
        failureCode = reservationFailureCode(reserved);
        requestedAt = null;
        responseReceivedAt = null;
        resultCode = null;
        providerRows = null;
        storedRows = null;
        excludedRows = null;
    }

    public void abandonBeforeSend() {
        outcome = CollectionBatchStatus.ABANDONED_BEFORE_SEND;
    }

    public void conclude(CollectionResult collectionResult, OffsetDateTime receivedAt) {
        outcome = collectionResult.outcome();
        failureCode = collectionResult.failureCode();
        resultCode = collectionResult.upstreamResultCode();
        responseReceivedAt = receivedAt;
    }

    public void countRows(ObservationRows observationRows) {
        providerRows = observationRows.providerRows();
        storedRows = observationRows.storableRowCount();
        excludedRows = observationRows.excludedRowCount();
    }

    public Long id() {
        return id;
    }

    public long routeVersionId() {
        return routeVersionId;
    }

    /**
     * 현재 수집 생명주기의 저장 가능한 스냅샷을 만든다. 시도·응답 상태와 읽어 온 입력 확인 시각을 하나의 판본으로 전달한다.
     */
    public CollectionBatch.State state() {
        return new CollectionBatch.State(
                id,
                routeVersionId,
                scheduledAt,
                attemptKey,
                attemptNumber,
                requestedAt,
                responseReceivedAt,
                inputConfirmedAt,
                outcome,
                failureCode,
                resultCode,
                providerRows,
                storedRows,
                excludedRows,
                normalizationVersion,
                collectionStrategyVersion
        );
    }

    public record State(
        Long id,
        long routeVersionId,
        OffsetDateTime scheduledAt,
        String attemptKey,
        int attemptNumber,
        OffsetDateTime requestedAt,
        OffsetDateTime responseReceivedAt,
        OffsetDateTime inputConfirmedAt,
        CollectionBatchStatus outcome,
        CollectionFailureReason failureCode,
        Integer resultCode,
        Integer providerRows,
        Integer storedRows,
        Integer excludedRows,
        String normalizationVersion,
        String collectionStrategyVersion
    ) {

    }
}
