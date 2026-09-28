package com.gustler.backend.forecasting.domain.evaluation;

import java.util.List;

/** 발행한 예측은 바꾸지 않고, 평가 대상을 조회하고 확정 결과를 저장한다. */
public interface ForecastEvaluationRepository {

    /** 현재 노선 버전뿐 아니라 미완료 평가가 남은 이전 버전도 반환한다. */
    List<Long> findRouteVersionIdsWithPendingForecasts();

    /** 평가 대상 관측이 속한 노선을 조회한다. 잠금 순서는 응용 서비스가 정한다. */
    List<Long> findRouteIdsForObservations(List<Long> observationIds);

    List<PendingForecast> findPending(long routeVersionId, int limit);

    /** PENDING에서 새로 확정한 결과를 반환한다. */
    List<SettledEvaluation> settle(List<ForecastEvaluation> evaluations);
}
