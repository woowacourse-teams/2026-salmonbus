package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.statistics.DemandSample;
import com.gustler.backend.forecasting.infrastructure.statistics.StatisticsInputRow.Observation;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 파일의 통계 원본 행을 범위와 적격성 규칙에 따라 표본으로 해석한다.
 */
record StatisticsInputRow(
    long predictionObservationId,
    int targetStopOrder,
    int stopsToTarget,
    boolean boardingAllowed,
    ScoringState state,
    Long arrivalObservationId,
    Integer seatsOnArrival,
    Instant arrivedAt,
    Instant scoredAt,
    long qualityRevision,
    Observation source,
    Observation arrival
) {
    record Observation(long id, long routeVersionId, String vehicleId,
        long qualityDirection, Integer remainingSeats, boolean usable) {
    }

    StatisticsInputRow {
        if (!hasValidIdentity(predictionObservationId, targetStopOrder, stopsToTarget, qualityRevision, state)) {
            throw new IllegalArgumentException("정산 식별자와 판정 정보가 올바르지 않다");
        }
        if (source == null || source.id() != predictionObservationId) {
            throw new IllegalArgumentException("정산이 참조하는 원 관측이 없거나 다르다");
        }
        if (hasMismatchedArrival(arrivalObservationId, arrival)) {
            throw new IllegalArgumentException("정산이 참조하는 도착 관측이 없거나 다르다");
        }
        if (arrivalObservationId == null && arrival != null) {
            throw new IllegalArgumentException("정산에 없는 도착 관측이 연결됐다");
        }
        if (state.scorable() && !hasScoringResult(arrivalObservationId, seatsOnArrival, arrivedAt, scoredAt)) {
            throw new IllegalArgumentException("정상 정산에는 도착 관측과 잔여석, 도착·정산 시각이 필요하다");
        }
    }

    private static boolean hasValidIdentity(
        long predictionObservationId,
        int targetStopOrder,
        int stopsToTarget,
        long qualityRevision,
        ScoringState state
    ) {
        if (predictionObservationId <= 0 || targetStopOrder < 1) {
            return false;
        }
        if (stopsToTarget < 1 || qualityRevision < 1) {
            return false;
        }
        return state != null;
    }

    private static boolean hasMismatchedArrival(Long arrivalObservationId, Observation arrival) {
        if (arrivalObservationId == null) {
            return false;
        }
        return arrival == null || arrival.id() != arrivalObservationId;
    }

    private static boolean hasScoringResult(
        Long arrivalObservationId,
        Integer seatsOnArrival,
        Instant arrivedAt,
        Instant scoredAt
    ) {
        if (arrivalObservationId == null || seatsOnArrival == null) {
            return false;
        }
        if (seatsOnArrival < 0 || arrivedAt == null) {
            return false;
        }
        return scoredAt != null;
    }

    Optional<DemandSample> sample(StatisticsInputScope scope) {
        if (source.routeVersionId() != scope.routeVersionId()) {
            throw new IllegalArgumentException("다른 노선 버전의 자료가 섞였다");
        }
        if (qualityRevision != scope.qualityRevision()) {
            throw new IllegalArgumentException("다른 품질 판본의 자료가 섞였다");
        }
        if (!state.scorable() || scoredAt.isAfter(scope.dataUntil())) {
            return Optional.empty();
        }
        if (!hasMatchingObservations(source, arrival)) {
            return Optional.empty();
        }
        return DemandSample.fromSettlement(scope.routeVersionId(), predictionObservationId,
            arrivalObservationId, source.vehicleId(), targetStopOrder, stopsToTarget,
            boardingAllowed, arrivedAt, scoredAt, source.remainingSeats(), seatsOnArrival);
    }

    private static boolean hasMatchingObservations(Observation source, Observation arrival) {
        if (!source.usable() || !arrival.usable()) {
            return false;
        }
        if (source.routeVersionId() != arrival.routeVersionId()) {
            return false;
        }
        if (!Objects.equals(source.vehicleId(), arrival.vehicleId())) {
            return false;
        }
        return source.qualityDirection() == arrival.qualityDirection();
    }
}
