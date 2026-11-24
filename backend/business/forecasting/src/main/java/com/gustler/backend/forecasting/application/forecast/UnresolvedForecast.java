package com.gustler.backend.forecasting.application.forecast;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 정답을 받지 못하고 정산을 마친 예측 하나(관측, 목표 정류장)다.
 *
 * @param sourceDate 예보의 파티션 기준 날짜
 * @param vehicleObservationId 예측의 기준 관측
 * @param targetStopOrder 목표 정류장 순번
 * @param routeId 노선
 * @param state 닫힌 상태
 * @param reason 사유
 * @param closedAt 닫은 시각
 */
public record UnresolvedForecast(
    LocalDate sourceDate,
    long vehicleObservationId,
    int targetStopOrder,
    long routeId,
    State state,
    String reason,
    Instant closedAt
) {

    public UnresolvedForecast {
        Objects.requireNonNull(sourceDate, "나눔 날짜가 필요하다");
        Objects.requireNonNull(state, "닫힌 상태가 필요하다");
        Objects.requireNonNull(closedAt, "닫은 시각이 필요하다");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("사유가 필요하다");
        }
    }

    /** 닫힌 상태. 이름은 {@code forecast_unresolved.state}에 그대로 저장된다. */
    public enum State {

        /** 규칙상 정산할 수 없는 예측이다. */
        UNSUPPORTED,
        /** 기다리는 동안 통과를 받지 못했다. */
        LOST
    }
}
