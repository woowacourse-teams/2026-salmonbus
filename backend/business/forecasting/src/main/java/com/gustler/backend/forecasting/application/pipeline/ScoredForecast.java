package com.gustler.backend.forecasting.application.pipeline;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 예측 하나(관측, 목표 정류장)가 어떤 통과 정답에 짝지어졌는지다. 저장하지 않고 지표와 대조에만 쓴다.
 *
 * <p>정답은 ({@code passSourceDate}, {@code routeId}, {@code tripStartObservationId}, {@code stopOrder}, {@code capacity},
 * {@code labelVersion})으로 {@link PassLabel}을 찾는다.
 *
 * @param sourceDate 예보의 나눔 날짜
 * @param vehicleObservationId 예측의 기준 관측
 * @param targetStopOrder 목표 정류장 순번
 * @param routeId 노선
 * @param passSourceDate 통과의 나눔 날짜
 * @param tripStartObservationId 통과의 운행 시작 관측
 * @param stopOrder 통과의 정류장 순번
 * @param capacity 정답을 계산한 정원
 * @param labelVersion 정답 판
 */
public record ScoredForecast(
    LocalDate sourceDate,
    long vehicleObservationId,
    int targetStopOrder,
    long routeId,
    LocalDate passSourceDate,
    long tripStartObservationId,
    int stopOrder,
    int capacity,
    String labelVersion
) {
    public ScoredForecast {
        Objects.requireNonNull(sourceDate, "예보의 나눔 날짜가 필요하다");
        Objects.requireNonNull(passSourceDate, "통과의 나눔 날짜가 필요하다");
        Objects.requireNonNull(labelVersion, "정답 판이 필요하다");
    }
}
