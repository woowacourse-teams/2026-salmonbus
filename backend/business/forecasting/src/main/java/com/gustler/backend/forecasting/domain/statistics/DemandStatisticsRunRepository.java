package com.gustler.backend.forecasting.domain.statistics;

import java.util.Optional;

public interface DemandStatisticsRunRepository {

    Optional<DemandStatisticsRun> find(long routeVersionId);

    void save(DemandStatisticsRun run);
}
