package com.gustler.backend.forecasting.domain.statistics;

import java.util.Optional;

/**
 * 노선 통계 실행의 단계와 처리 커서를 저장하고 복원하는 경계다.
 */
public interface DemandStatisticsRunRepository {
    /**
     * 노선의 통계 실행 상태를 찾는다. 아직 실행하지 않은 노선은 빈 값이다.
     */
    Optional<DemandStatisticsRun> find(long routeVersionId);

    /**
     * 통계 실행의 단계와 모든 커서를 같은 스냅샷으로 저장한다.
     */
    void save(DemandStatisticsRun run);
}
