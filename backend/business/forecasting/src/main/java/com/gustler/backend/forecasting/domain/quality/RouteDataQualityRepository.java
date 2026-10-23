package com.gustler.backend.forecasting.domain.quality;

/**
 * 노선 품질 상태를 잠가 읽고 변경된 판본을 저장하는 경계다.
 */
public interface RouteDataQualityRepository {
    /**
     * 변경할 품질 상태를 잠가 읽는다. 조회를 끝냈다고 잠금이 해제되는 것은 아니며 호출 트랜잭션에 종속된다.
     */
    RouteDataQuality findForUpdate(long routeVersionId);

    /**
     * 품질 판본과 조사 상태를 함께 저장한다. 관련 관측·통계 무효화와 같은 트랜잭션에서 호출한다.
     */
    void save(RouteDataQuality quality);
}
