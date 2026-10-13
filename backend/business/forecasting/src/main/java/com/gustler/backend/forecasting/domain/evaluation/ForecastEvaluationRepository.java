package com.gustler.backend.forecasting.domain.evaluation;

import java.util.List;

/** 발행한 예측은 바꾸지 않고, 평가 대상을 조회하고 확정 결과를 저장한다. */
public interface ForecastEvaluationRepository {

    /** 현재 노선 버전뿐 아니라 미완료 평가가 남은 이전 버전도 반환한다. */
    List<Long> findRouteVersionIdsWithPendingForecasts();

    /** 평가 대상 관측이 속한 노선을 조회한다. 잠금 순서는 응용 서비스가 정한다. */
    List<Long> findRouteIdsForObservations(List<Long> observationIds);

    /** 정산 트랜잭션에서 호출한다. 읽은 범위의 확정 제외 평가를 제한 개수만 종료한다. */
    List<PendingForecast> findPending(long routeVersionId, int limit);

    /** 같은 노선의 정산과 직렬화하도록 노선 잠금을 확보한 트랜잭션에서 호출한다. */
    void addPending(long routeVersionId, List<ForecastEvaluation> evaluations);

    /** 노선 잠금을 확보한 트랜잭션에서 대기를 제거하고 새로 확정한 결과를 반환한다. */
    List<SettledEvaluation> settle(List<ForecastEvaluation> evaluations);
}
