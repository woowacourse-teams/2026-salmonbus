package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.statistics.DemandSamplePage;
import com.gustler.backend.forecasting.domain.statistics.DemandSampleRepository;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun;
import com.gustler.backend.forecasting.domain.statistics.DemandSamplePage.PendingSample;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 한 차량의 고정된 처리 범위에서 한 페이지만 반영한다. 호출자가 다음 실행 기회를 정한다. */
@Component
public class DemandAccumulator {

    private final DemandStatisticsStore store;
    private final DemandSampleRepository samples;
    private final DemandStatisticsRebuildRequests requests;
    private final RouteDataQualityAccess quality;

    public DemandAccumulator(final DemandStatisticsStore store, final DemandSampleRepository samples,
        final DemandStatisticsRebuildRequests requests, final RouteDataQualityAccess quality) {
        this.store = store;
        this.samples = samples;
        this.requests = requests;
        this.quality = quality;
    }

    @Transactional(timeout = 2)
    public Result apply(final long routeVersionId, final String vehicleId, final long afterInputId,
        final long inputUntilId, final Instant dataUntil) {
        store.limitStatementTime();
        quality.lock(routeVersionId);
        if (quality.investigationPending(routeVersionId, vehicleId)
            || requests.blocksAccumulation(routeVersionId, vehicleId)) {
            return Result.waiting(afterInputId);
        }
        final DemandSamplePage page = samples.lockPage(routeVersionId, vehicleId, afterInputId, inputUntilId,
            DemandStatisticsRun.PAGE_SIZE);
        if (page.requiresRebuild()) {
            requests.request(routeVersionId, new RebuildScope(vehicleId));
            return new Result(page.size(), 0, true, page.nextInputId(afterInputId));
        }
        final List<PendingSample> applicable = page.applicableUntil(dataUntil);
        if (!applicable.isEmpty()) {
            store.addToCurrentTotals(routeVersionId, page.totalsUntil(dataUntil));
            store.registerVehicle(routeVersionId, vehicleId);
            samples.remove(applicable.stream().map(PendingSample::id).toList());
        }
        return new Result(page.size(), applicable.size(), false, page.nextInputId(afterInputId));
    }

    public record Result(int selected, int applied, boolean waitingForRebuild, long nextInputId) {

        static Result waiting(final long afterInputId) {
            return new Result(0, 0, true, afterInputId);
        }
    }
}
