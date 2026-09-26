package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/** 외부 조회를 분류한 결과와 정규화한 관측. 실패한 조회에는 정상 응답의 행 수를 기록하지 않는다. */
public record ObservationResponse(ObservationBatchConclusion conclusion,
                                  Optional<CollectedObservations> observations, OffsetDateTime receivedAt) {
    public ObservationResponse {
        Objects.requireNonNull(conclusion);
        Objects.requireNonNull(observations);
        Objects.requireNonNull(receivedAt);
        final boolean successful = conclusion.outcome() == ObservationBatchOutcome.SUCCESS_ROWS
            || conclusion.outcome() == ObservationBatchOutcome.SUCCESS_EMPTY;
        if (successful != observations.isPresent()) {
            throw new IllegalArgumentException("정상 수집 응답에는 정규화한 관측 결과가 필요하다");
        }
    }

    public static ObservationResponse received(ObservationBatchConclusion conclusion,
                                                CollectedObservations observations, OffsetDateTime receivedAt) {
        return new ObservationResponse(conclusion, Optional.of(observations), receivedAt);
    }

    public static ObservationResponse failed(ObservationBatchConclusion conclusion, OffsetDateTime receivedAt) {
        return new ObservationResponse(conclusion, Optional.empty(), receivedAt);
    }

    public static ObservationResponse unconfirmed(OffsetDateTime receivedAt) {
        return failed(new ObservationBatchConclusion(ObservationBatchOutcome.UNKNOWN_AFTER_DISPATCH, null, null), receivedAt);
    }
}
