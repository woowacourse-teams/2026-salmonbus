package com.gustler.backend.forecasting.domain.evaluation;

import java.util.List;

/** 발행한 예측은 바꾸지 않고, 평가 대상을 조회하고 확정 결과를 저장한다. */
public interface ForecastEvaluationRepository {

    /** 현재 노선 버전뿐 아니라 미완료 평가가 남은 이전 버전도 반환한다. */
    List<Long> findRouteVersionIdsWithPendingForecasts();

    List<PendingForecast> findPending(long routeVersionId, int limit);

    /** PENDING에서 새로 확정한 결과 중 현재 당일 보정에 사용할 수 있는 결과만 반환한다. */
    List<SettledForecast> settle(List<ForecastEvaluation> evaluations);
}
