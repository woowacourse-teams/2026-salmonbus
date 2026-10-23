package com.gustler.backend.forecasting.application.quality;

/**
 * 노선 품질 판본과 조사 여부를 읽고 잠그는 경계다.
 * 판본과 재사용 자료의 일관성을 확인할 때 사용한다.
 */
public interface RouteDataQualityAccess {
    /** 잠금 없이 현재 품질 버전을 읽는다. 행이 아직 없으면 최초 버전인 1이다. */
    long current(long routeVersionId);

    /**
     * 노선 품질 상태 행을 없을 때만 준비한다. 이후 잠금·판본 갱신과 같은 트랜잭션에서 사용한다.
     */
    void ensureExists(long routeVersionId);

    /**
     * 노선 판본의 품질 상태를 잠그고 현재 품질 판본을 반환한다. 호출 트랜잭션이 잠금 수명을 정한다.
     */
    long lock(long routeVersionId);

    /**
     * 노선 식별자에 속한 품질 상태를 잠근다. 판본 식별자 기반 잠금과 입력 의미를 구별한다.
     */
    long lockByRoute(long routeId);

    boolean anyInvestigationPending(long routeVersionId);

    boolean investigationPending(long routeVersionId, String vehicleId);
}
