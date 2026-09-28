package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild.ScanWindow;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DemandRebuildStore {

    record BatchPosition(Instant at, long id) {
    }

    record Page(int size, long lastId) {
    }

    int clearLeftoverTotals(long routeVersionId, RebuildScope scope, int limit);

    long lastObservationId();

    long lastBatchId();

    List<Long> observationPage(long afterObservationId, long observationUntilId, int limit);

    Optional<BatchPosition> nextGroupEnd(long routeVersionId, ScanWindow window, int groupSize);

    List<Long> groupObservationPage(long routeVersionId, ScanWindow window, RebuildScope scope,
        long afterObservationId, long observationUntilId, int groupSize, int limit);

    void addRebuildTotals(DemandStatisticsRebuild rebuild, List<Long> observationIds);

    int clearCurrentTotals(long routeVersionId, RebuildScope scope, int limit);

    Page copyToCurrentTotals(UUID requestId, long afterTotalId, int limit);

    Page acknowledgeSamples(long routeVersionId, RebuildScope scope, long afterSampleId, long inputUntilId,
        Instant dataUntil, int limit);

    int cleanRebuildTotals(UUID requestId, int limit);
}
