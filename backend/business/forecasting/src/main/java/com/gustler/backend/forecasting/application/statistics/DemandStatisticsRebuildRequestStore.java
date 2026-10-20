package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import java.util.Optional;
import java.util.UUID;

/**
 * 노선·차량 범위의 재집계 요청과 현재 요청 식별을 관리한다.
 * 오래된 요청의 완료 처리를 구분한다.
 */
public interface DemandStatisticsRebuildRequestStore {
    /** 호출자와 같은 transaction에 저장한다. 새 요청은 이전 처리의 완료로 지우면 안 된다. */
    void request(long routeVersionId, RebuildScope scope);

    boolean isPending(long routeVersionId, RebuildScope scope);

    /**
     * 노선 전체 또는 해당 차량의 미완료 재구축이 증분 반영을 막는지 확인한다.
     */
    boolean blocksAccumulation(long routeVersionId, String vehicleId);

    /**
     * 다음에 처리할 미완료 재구축 범위를 찾는다. 처리 대상이 없으면 빈 값이다.
     */
    Optional<RebuildScope> next(long routeVersionId);

    /**
     * 해당 범위의 현재 요청 식별자를 읽는다. 요청이 없으면 빈 값이다.
     */
    Optional<UUID> current(long routeVersionId, RebuildScope scope);

    /**
     * 현재 요청 식별자가 일치하는 재구축만 완료 처리한다. 도중에 갱신된 새 요청을 지우지 않는다.
     */
    void complete(long routeVersionId, RebuildScope scope, UUID requestId);

    /**
     * 노선 품질 변경에 맞춰 차량 재구축 요청을 갱신한다. 이전 품질 판본의 진행을 그대로 완료 처리하지 않는다.
     */
    void renewVehicleRequests(long routeVersionId);
}
