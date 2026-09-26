package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
        if (routeVersionId <= 0 || attemptNumber <= 0) {
            throw new IllegalArgumentException("노선 버전 ID와 수집 시도 횟수는 양수여야 한다");
        }
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

    public void requireCanStartAttempt() {
        if (inputConfirmedAt != null) {
            throw new InputAlreadyConfirmedException(id, inputConfirmedAt);
        }
    }

    public void startAttempt(final boolean reserved) {
        requireCanStartAttempt();
        attemptNumber = Math.incrementExact(attemptNumber);
        outcome = reserved ? ObservationBatchOutcome.RESERVED : ObservationBatchOutcome.NOT_RESERVED;
        failureCode = reserved ? null : ObservationBatchFailureCode.LOCAL_QUOTA_EXHAUSTED;
        requestedAt = null;
        responseReceivedAt = null;
        resultCode = null;
        providerRows = null;
        storedRows = null;
        excludedRows = null;
    }

    public CollectionAttemptToken token() {
        return new CollectionAttemptToken(Objects.requireNonNull(id, "저장 전에는 수집 시도 ID가 없다"), attemptNumber);
    }

    public void requireAttempt(CollectionAttemptToken token) {
        if (!Objects.equals(id, token.batchId()) || token.attemptNumber() != attemptNumber) {
            throw new StaleCollectionAttemptException(token, attemptNumber);
        }
    }

    public boolean isAwaitingDispatch(CollectionAttemptToken token) {
        requireAttempt(token);
        if (outcome == ObservationBatchOutcome.DISPATCHING) {
            return false;
        }
        requireState(ObservationBatchOutcome.RESERVED, "전송 준비");
        return true;
    }

    public void dispatch(CollectionAttemptToken token, OffsetDateTime sentAt) {
        requireAttempt(token);
        requireState(ObservationBatchOutcome.RESERVED, "전송");
        requestedAt = canonicalTime(sentAt);
        outcome = ObservationBatchOutcome.DISPATCHING;
    }

    public void abandonBeforeSend(CollectionAttemptToken token) {
        requireAttempt(token);
        if (outcome == ObservationBatchOutcome.ABANDONED_BEFORE_SEND) {
            return;
        }
        requireState(ObservationBatchOutcome.RESERVED, "전송 취소");
        outcome = ObservationBatchOutcome.ABANDONED_BEFORE_SEND;
    }

    /** 이미 저장한 동일 결과이면 false를 반환해 관측과 품질 조사 등록의 중복을 막는다. */
    public boolean completeAttempt(CollectionAttemptToken token, ObservationBatchConclusion conclusion,
                                   OffsetDateTime receivedAt, Integer provided, Integer stored, Integer excluded) {
        requireAttempt(token);
        receivedAt = canonicalTime(receivedAt);
        requireCompletion(conclusion, provided, stored, excluded);
        if (isTerminal()) {
            if (outcome == conclusion.outcome() && failureCode == conclusion.failureCode()
                && Objects.equals(resultCode, conclusion.upstreamResultCode())
                && sameInstant(responseReceivedAt, receivedAt) && Objects.equals(providerRows, provided)
                && Objects.equals(storedRows, stored) && Objects.equals(excludedRows, excluded)) {
                return false;
            }
            throw new ConflictingCollectionResultException(id);
        }
        requireState(ObservationBatchOutcome.DISPATCHING, "수집 완료");
        outcome = conclusion.outcome();
        failureCode = conclusion.failureCode();
        resultCode = conclusion.upstreamResultCode();
        responseReceivedAt = receivedAt;
        providerRows = provided;
        storedRows = stored;
        excludedRows = excluded;
        return true;
    }

    public void confirmInput(CollectionAttemptToken token, OffsetDateTime confirmedAt) {
        requireAttempt(token);
        if (!successful()) {
            throw new InvalidCollectionStateException(id, outcome, "입력 확정");
        }
        if (inputConfirmedAt == null) {
            inputConfirmedAt = canonicalTime(confirmedAt);
        }
    }

    public boolean successful() {
        return outcome == ObservationBatchOutcome.SUCCESS_ROWS || outcome == ObservationBatchOutcome.SUCCESS_EMPTY;
    }

    private boolean isTerminal() {
        return outcome != ObservationBatchOutcome.RESERVED && outcome != ObservationBatchOutcome.DISPATCHING;
    }

    private void requireState(ObservationBatchOutcome expected, String action) {
        if (outcome != expected) {
            throw new InvalidCollectionStateException(id, outcome, action);
        }
    }

    private static OffsetDateTime canonicalTime(OffsetDateTime time) {
        // 저장 후 다시 전달한 결과도 같은 시각으로 비교할 수 있도록 마이크로초 단위를 사용한다.
        return Objects.requireNonNull(time).truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean sameInstant(OffsetDateTime first, OffsetDateTime second) {
        return first != null && first.toInstant().equals(second.toInstant());
    }

    private static void requireCompletion(ObservationBatchConclusion conclusion,
                                          Integer provided, Integer stored, Integer excluded) {
        if (conclusion.outcome() == ObservationBatchOutcome.SUCCESS_ROWS
            || conclusion.outcome() == ObservationBatchOutcome.SUCCESS_EMPTY) {
            if (provided == null || stored == null || excluded == null || provided < 0 || stored < 0 || excluded < 0
                || provided != stored + excluded || conclusion.failureCode() != null
                || conclusion.outcome() != ObservationBatchOutcome.forProviderRows(provided)) {
                throw new IllegalArgumentException("정상 수집 결과의 행 수가 일치하지 않는다");
            }
        } else if (conclusion.outcome() != ObservationBatchOutcome.UNKNOWN_AFTER_DISPATCH
            && conclusion.outcome() != ObservationBatchOutcome.FAILED_UPSTREAM
            && conclusion.outcome() != ObservationBatchOutcome.FAILED_UNREADABLE) {
            throw new IllegalArgumentException("수집 완료 결과가 아니다: " + conclusion.outcome());
        } else if (provided != null || stored != null || excluded != null) {
            throw new IllegalArgumentException("실패한 수집에는 정상 응답의 행 수를 기록할 수 없다");
        }
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
