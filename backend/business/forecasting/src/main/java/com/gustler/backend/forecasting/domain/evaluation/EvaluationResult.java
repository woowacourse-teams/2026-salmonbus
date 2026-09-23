package com.gustler.backend.forecasting.domain.evaluation;

import java.util.Objects;

/** 확정한 평가 결과. 도착을 확인한 경우에만 해당 관측과 좌석 정보를 가진다. */
public record EvaluationResult(
    ScoringState state,
    Long arrivalObservationId,
    Integer seatsOnArrival
) {

    public EvaluationResult {
        Objects.requireNonNull(state, "평가 상태가 필요합니다");
        switch (state) {
            case PENDING -> throw new IllegalArgumentException("평가 대기는 확정 결과가 아닙니다");
            case SETTLED -> {
                requireArrivalObservation(arrivalObservationId);
                if (seatsOnArrival == null || seatsOnArrival < 0) {
                    throw new IllegalArgumentException("좌석을 확인한 평가에는 0 이상의 잔여석이 필요합니다");
                }
            }
            case SEAT_MISSING -> {
                requireArrivalObservation(arrivalObservationId);
                if (seatsOnArrival != null) {
                    throw new IllegalArgumentException("좌석 결측 결과에는 잔여석을 기록할 수 없습니다");
                }
            }
            case SKIPPED, LOST -> {
                if (arrivalObservationId != null || seatsOnArrival != null) {
                    throw new IllegalArgumentException("도착을 확인하지 못한 결과에는 도착 관측과 잔여석을 기록할 수 없습니다");
                }
            }
        }
    }

    public static EvaluationResult from(ArrivalLabel label) {
        Objects.requireNonNull(label, "도착 판정이 필요합니다");
        return switch (label) {
            case ArrivalLabel.Settled settled ->
                new EvaluationResult(ScoringState.SETTLED, settled.arrivalObservationId(), settled.seatsOnArrival());
            case ArrivalLabel.SeatMissing missing ->
                new EvaluationResult(ScoringState.SEAT_MISSING, missing.arrivalObservationId(), null);
            case ArrivalLabel.Skipped ignored -> new EvaluationResult(ScoringState.SKIPPED, null, null);
            case ArrivalLabel.Lost ignored -> new EvaluationResult(ScoringState.LOST, null, null);
            case ArrivalLabel.NotArrivedYet ignored ->
                throw new IllegalArgumentException("아직 도착하지 않은 예보의 평가는 대기 상태로 유지합니다");
        };
    }

    private static void requireArrivalObservation(Long arrivalObservationId) {
        if (arrivalObservationId == null || arrivalObservationId <= 0) {
            throw new IllegalArgumentException("도착을 확인한 평가에는 유효한 관측 ID가 필요합니다");
        }
    }
}
