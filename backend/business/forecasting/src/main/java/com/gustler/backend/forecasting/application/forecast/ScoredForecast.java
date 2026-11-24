package com.gustler.backend.forecasting.application.forecast;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 예측 하나(관측, 목표 정류장)가 어떤 통과 정답에 짝지어졌는지다. 저장하지 않는다. 지표가 짝지어진 예측을 정답의 상태별로 센다.
 *
 * <p>정답은 ({@code passSourceDate}, {@code routeId}, {@code tripStartObservationId}, {@code stopOrder}, {@code capacity},
 * {@code modelDeploymentId})과 정산 버전 {@link ScoringReasons#LABEL_VERSION}으로 {@link PassLabel}을 찾는다.
 *
 * @param vehicleObservationId 예측의 기준 관측
 * @param targetStopOrder 목표 정류장 순번
 * @param routeId 노선
 * @param passSourceDate 통과의 파티션 기준 날짜
 * @param tripStartObservationId 통과의 운행 시작 관측
 * @param stopOrder 통과의 정류장 순번
 * @param capacity 정답을 계산한 정원
 * @param modelDeploymentId 예측을 낸 모델 배포. 정답도 이 배포의 계수로 계산했다
 */
public record ScoredForecast(
    long vehicleObservationId,
    int targetStopOrder,
    long routeId,
    LocalDate passSourceDate,
    long tripStartObservationId,
    int stopOrder,
    int capacity,
    long modelDeploymentId
) {

    public ScoredForecast {
        Objects.requireNonNull(passSourceDate, "통과의 파티션 기준 날짜가 필요하다");
    }
}
