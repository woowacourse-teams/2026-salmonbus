package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 저장된 {@code forecast_run} 한 줄이다. 배치 하나를 예보 단계가 처리한 결과와 착수 시각을 담는다.
 *
 * @param sourceDate 배치 예약 시각의 한국 날짜
 * @param observationBatchId 처리한 관측 배치
 * @param routeId 노선
 * @param routeVersionId 노선 판본
 * @param receivedAt 배치 응답 수신 시각
 * @param confirmedAt 예보 착수 시각
 * @param modelDeploymentId 모델 배포. 없을 수 있다
 * @param outcome 처리 결과
 * @param predictionCount (관측, 목표 정류장) 예측의 수
 */
public record ForecastRun(
    LocalDate sourceDate,
    long observationBatchId,
    long routeId,
    long routeVersionId,
    Instant receivedAt,
    Instant confirmedAt,
    Long modelDeploymentId,
    ForecastOutcome outcome,
    int predictionCount
) {
    public ForecastRun {
        Objects.requireNonNull(sourceDate, "배치 날짜가 필요하다");
        Objects.requireNonNull(receivedAt, "수신 시각이 필요하다");
        Objects.requireNonNull(confirmedAt, "착수 시각이 필요하다");
        Objects.requireNonNull(outcome, "처리 결과가 필요하다");
    }
}
