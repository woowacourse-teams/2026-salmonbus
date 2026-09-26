package com.gustler.backend.forecasting.domain.evaluation;

import java.util.List;

/** 발행한 예측은 바꾸지 않고, 평가 대상을 조회하고 확정 결과를 저장한다. */
public interface ForecastEvaluationRepository {

    /** 현재 노선 버전뿐 아니라 미완료 평가가 남은 이전 버전도 반환한다. */
    List<EvaluationRoute> findRoutesWithPendingForecasts();

    /** 평가 대상 관측이 속한 노선을 조회한다. 잠금 순서는 응용 서비스가 정한다. */
    List<Long> findRouteIdsForObservations(List<Long> observationIds);

    /** 현재 평가 상태와 관측의 품질 조건으로 완료할 수 있는지 확인한다. */
    boolean canComplete(ForecastEvaluation evaluation);

    List<PendingForecast> findPending(long routeVersionId, int limit);

    /** PENDING에서 새로 확정한 결과 중 현재 당일 보정에 사용할 수 있는 결과만 반환한다. */
    List<SettledForecast> settle(List<ForecastEvaluation> evaluations);
}
