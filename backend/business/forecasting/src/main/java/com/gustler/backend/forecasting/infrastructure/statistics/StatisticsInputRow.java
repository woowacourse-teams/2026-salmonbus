package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.statistics.DemandSample;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

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
        if (predictionObservationId <= 0 || targetStopOrder < 1 || stopsToTarget < 1
            || qualityRevision < 1 || state == null) {
            throw new IllegalArgumentException("정산 식별자와 판정 정보가 올바르지 않다");
        }
        if (source == null || source.id() != predictionObservationId) {
            throw new IllegalArgumentException("정산이 참조하는 원 관측이 없거나 다르다");
        }
        if (arrivalObservationId != null
            && (arrival == null || arrival.id() != arrivalObservationId)) {
            throw new IllegalArgumentException("정산이 참조하는 도착 관측이 없거나 다르다");
        }
        if (arrivalObservationId == null && arrival != null) {
            throw new IllegalArgumentException("정산에 없는 도착 관측이 연결됐다");
        }
        if (state.scorable() && (arrivalObservationId == null || seatsOnArrival == null
            || seatsOnArrival < 0 || arrivedAt == null || scoredAt == null)) {
            throw new IllegalArgumentException("정상 정산에는 도착 관측과 잔여석, 도착·정산 시각이 필요하다");
        }
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
        if (!source.usable() || !arrival.usable()
            || source.routeVersionId() != arrival.routeVersionId()
            || !Objects.equals(source.vehicleId(), arrival.vehicleId())
            || source.qualityDirection() != arrival.qualityDirection()) {
            return Optional.empty();
        }
        return DemandSample.fromSettlement(scope.routeVersionId(), predictionObservationId,
            arrivalObservationId, source.vehicleId(), targetStopOrder, stopsToTarget,
            boardingAllowed, arrivedAt, scoredAt, source.remainingSeats(), seatsOnArrival);
    }
}
