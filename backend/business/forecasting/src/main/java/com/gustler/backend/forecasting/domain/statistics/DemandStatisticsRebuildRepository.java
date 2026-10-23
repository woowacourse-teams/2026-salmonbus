package com.gustler.backend.forecasting.domain.statistics;

import java.util.Optional;

/**
 * 재집계 요청의 범위·판본·단계·커서를 저장하고 복원하는 경계다.
 */
public interface DemandStatisticsRebuildRepository {
    /**
     * 노선 판본과 재구축 범위의 저장된 진행 상태를 찾는다. 없으면 빈 값이다.
     */
    Optional<DemandStatisticsRebuild> find(long routeVersionId, RebuildScope scope);

    /**
     * 요청 식별자·상한·단계·커서를 함께 저장한다. 단계별 합계 변경과 같은 트랜잭션에서 호출한다.
     */
    void save(DemandStatisticsRebuild rebuild);

    /**
     * 지정 범위의 재구축 진행 상태를 제거한다. 통계 원본 자체의 삭제와는 구분한다.
     */
    void delete(long routeVersionId, RebuildScope scope);
}
