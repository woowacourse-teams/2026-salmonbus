package com.gustler.backend.forecasting.domain.evaluation;

import java.time.Instant;
import java.util.List;

/**
 * 당일 평가 집계의 조회·초기화·증분 저장을 담당한다.
 */
public interface SameDayFullOutcomesStore {
    /**
     * 당일 성적 계산 대상으로 사용할 활성 노선 식별자를 읽는다.
     */
    List<Long> findActiveRouteIds();

    /**
     * 지정 노선·모델의 날짜 범위에 속하는 확정 성적을 읽는다. 다른 날짜의 관측을 섞지 않는다.
     */
    List<SameDayFullOutcomeCount> findCounts(
        long routeId,
        long modelDeploymentId,
        SeoulDay day
    );

    /** 원본에서 다시 집계한 값으로 교체한다. 기존 누계에 더하지 않는다. */
    void upsertCounts(
        long routeId,
        long modelDeploymentId,
        SeoulDay day,
        List<SameDayFullOutcomeCount> counts
    );

    /**
     * 평가가 확정된 예보 하나를 집계에 더한다.
     *
     * <p>날짜와 반영 시각은 도착 관측이 포함된 batch가 응답을 받은 시각으로 정한다. 채점한 시각을 쓰면
     * 평가가 늦게 실행될 때 아직 도착하지 않은 관측까지 집계한 것처럼 기록된다.
     */
    void add(
        SettledForecast settled
    );

    /** 이미 초기화된 날짜의 누계에 새 정산 증가분을 더한다. 호출자는 노선 잠금을 유지한다. */
    void addCounts(
        long routeId,
        long modelDeploymentId,
        SeoulDay day,
        List<SameDayFullOutcomeCount> increments
    );

    /** 저장된 누계 대신 현재 품질 조건에 맞는 평가 결과에서 직접 집계한다. */
    List<SameDayFullOutcomeCount> countFromSource(
        long routeId,
        long modelDeploymentId,
        SeoulDay day,
        Instant until
    );
}
