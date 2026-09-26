package com.gustler.backend.forecasting.domain.publication;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 하나의 수집 시도로 계산한 예보. 발행한 예측과 계산에 사용한 버전은 바꾸지 않는다. */
public record ForecastPublication(
    long sourceBatchId,
    int sourceAttemptNumber,
    long routeVersionId,
    long modelDeploymentId,
    int demandStatisticsRevision,
    long qualityRevision,
    Instant observedAt,
    Instant generatedAt,
    Instant publishedAt,
    List<SeatForecast> predictions
) {

    public ForecastPublication {
        if (sourceBatchId <= 0 || sourceAttemptNumber <= 0 || routeVersionId <= 0 || modelDeploymentId <= 0) {
            throw new IllegalArgumentException("발행에는 수집 배치·시도·노선 버전·모델 식별 정보가 필요하다");
        }
        if (demandStatisticsRevision < 0 || qualityRevision <= 0) {
            throw new IllegalArgumentException("통계 버전은 0 이상, 품질 버전은 1 이상이어야 한다");
        }
        Objects.requireNonNull(observedAt, "관측 시각이 필요하다");
        Objects.requireNonNull(generatedAt, "계산 시각이 필요하다");
        Objects.requireNonNull(publishedAt, "발행 시각이 필요하다");
        predictions = List.copyOf(predictions);
        Set<PredictionKey> keys = new HashSet<>();
        for (SeatForecast prediction : predictions) {
            if (prediction.routeVersionId() != routeVersionId
                || prediction.modelDeploymentId() != modelDeploymentId
                || prediction.demandStatisticsRevision() != demandStatisticsRevision
                || !prediction.generatedAt().equals(generatedAt)) {
                throw new IllegalArgumentException("한 발행의 예측은 같은 노선 버전·모델·통계·계산 시각을 사용해야 한다");
            }
            if (!keys.add(new PredictionKey(prediction.vehicleObservationId(), prediction.targetStopOrder()))) {
                throw new IllegalArgumentException("같은 차량 관측과 대상 정류장의 예측을 중복 발행할 수 없다");
            }
        }
    }

    public int predictionCount() {
        return predictions.size();
    }

    private record PredictionKey(long observationId, int stopOrder) { }
}
