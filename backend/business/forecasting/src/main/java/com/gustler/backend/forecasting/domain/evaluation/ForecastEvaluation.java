package com.gustler.backend.forecasting.domain.evaluation;

import java.time.Instant;
import java.util.Objects;

/** 한 예측의 평가를 관리한다. 확정한 결과와 평가 시각은 다시 변경할 수 없다. */
public final class ForecastEvaluation {

    private final long vehicleObservationId;
    private final int targetStopOrder;
    private EvaluationResult result;
    private Instant scoredAt;

    private ForecastEvaluation(final long vehicleObservationId, final int targetStopOrder) {
        if (vehicleObservationId <= 0 || targetStopOrder <= 0) {
            throw new IllegalArgumentException("평가에는 유효한 원 관측 ID와 대상 정류장 순번이 필요합니다");
        }
        this.vehicleObservationId = vehicleObservationId;
        this.targetStopOrder = targetStopOrder;
    }

    public static ForecastEvaluation pending(final long vehicleObservationId, final int targetStopOrder) {
        return new ForecastEvaluation(vehicleObservationId, targetStopOrder);
    }

    public static ForecastEvaluation completed(
        final long vehicleObservationId,
        final int targetStopOrder,
        ArrivalLabel label,
        Instant scoredAt
    ) {
        ForecastEvaluation evaluation = pending(vehicleObservationId, targetStopOrder);
        evaluation.complete(EvaluationResult.from(label), scoredAt);
        return evaluation;
    }

    public void complete(EvaluationResult result, Instant scoredAt) {
        if (this.result != null) {
            throw new IllegalStateException("이미 확정한 예보 평가를 변경할 수 없습니다");
        }
        Objects.requireNonNull(result, "평가 결과가 필요합니다");
        Objects.requireNonNull(scoredAt, "평가 시각이 필요합니다");
        this.result = result;
        this.scoredAt = scoredAt;
    }

    public long vehicleObservationId() {
        return vehicleObservationId;
    }

    public int targetStopOrder() {
        return targetStopOrder;
    }

    public ScoringState state() {
        return result == null ? ScoringState.PENDING : result.state();
    }

    public EvaluationResult result() {
        return result;
    }

    public Instant scoredAt() {
        return scoredAt;
    }
}
