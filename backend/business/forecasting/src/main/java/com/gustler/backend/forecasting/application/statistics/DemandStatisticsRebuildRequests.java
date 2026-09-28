package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import java.util.Optional;
import java.util.UUID;

public interface DemandStatisticsRebuildRequests {

    /** 호출자와 같은 transaction에 저장한다. 새 요청은 이전 처리의 완료로 지우면 안 된다. */
    void request(long routeVersionId, RebuildScope scope);

    boolean isPending(long routeVersionId, RebuildScope scope);

    boolean blocksAccumulation(long routeVersionId, String vehicleId);

    Optional<RebuildScope> next(long routeVersionId);

    Optional<UUID> current(long routeVersionId, RebuildScope scope);

    void complete(long routeVersionId, RebuildScope scope, UUID requestId);

    void renewVehicleRequests(long routeVersionId);
}
