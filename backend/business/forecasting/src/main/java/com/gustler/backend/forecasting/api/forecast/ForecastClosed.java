package com.gustler.backend.forecasting.api.forecast;

/**
 * 채점이 예측 하나(관측, 목표 정류장)를 닫았고 그 결과 줄이 새로 저장됐다는 사건이다. 사건 자체는 저장하지 않으며 진단 수신자의
 * 정확도 집계가 읽는다.
 *
 * <p>정답에 짝지어져 채점됨으로 닫힌 예측만 정답 값이 있다. 통과에 짝지어진 예측은 정답이 지원 안 함이어도 그 통과의 마지막 관측 좌석이
 * 있고, 통과 없이 닫힌 예측(놓침, 지원 안 함)은 둘 다 없다.
 *
 * @param routeId 노선
 * @param routeVersionId 예보한 배치의 노선 판본
 * @param modelDeploymentId 예측을 낸 모델 배포
 * @param targetStopOrder 목표 정류장 순번
 * @param stopsToTarget 기준 관측에서 지난 정류장부터 목표 정류장까지의 정류장 수
 * @param expectedSeats 예측한 기대 좌석
 * @param fullProbability 예측한 만석 확률
 * @param labelSeats 정답 값(하차 뒤 좌석). 채점됨으로 닫힌 예측에만 있다
 * @param departureSeats 짝지어진 통과의 마지막 관측 좌석. 출발할 때 좌석이 0이면 만석이다. 통과에 짝지어진 예측에만 있다
 */
public record ForecastClosed(
    long routeId,
    long routeVersionId,
    long modelDeploymentId,
    int targetStopOrder,
    int stopsToTarget,
    double expectedSeats,
    double fullProbability,
    Double labelSeats,
    Integer departureSeats
) {
}
