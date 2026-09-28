package com.gustler.backend.forecasting.domain.statistics;

import java.util.Optional;

public interface DemandStatisticsRebuildRepository {

    Optional<DemandStatisticsRebuild> find(long routeVersionId, RebuildScope scope);

    void save(DemandStatisticsRebuild rebuild);

    void delete(long routeVersionId, RebuildScope scope);
}
