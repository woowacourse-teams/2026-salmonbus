package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.domain.statistics.DemandSamplePage;
import com.gustler.backend.forecasting.domain.statistics.DemandSampleStore;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 한 차량의 고정된 처리 범위에서 한 페이지만 반영한다. 호출자가 다음 실행 기회를 정한다. */
@Component
public class DemandAccumulator {
    private final DemandStatisticsStore store;
    private final DemandSampleStore samples;
    private final RouteWorkAccess routeWork;

    public DemandAccumulator(DemandStatisticsStore store, DemandSampleStore samples, RouteWorkAccess routeWork) {
        this.store = store;
        this.samples = samples;
        this.routeWork = routeWork;
    }

    @Transactional(readOnly = false, timeout = 2)
    public Result apply(
        final long routeVersionId,
        String vehicleId,
        final long afterInputId,
        final long inputUntilId,
        Instant dataUntil
    ) {
        store.limitStatementTime();
        routeWork.lock(routeVersionId);
        DemandSamplePage page = samples.lockPage(routeVersionId, vehicleId, afterInputId, inputUntilId,
            DemandStatisticsRun.PAGE_SIZE);
        List<DemandSamplePage.PendingSample> applicable = page.applicableUntil(dataUntil);
        if (!applicable.isEmpty()) {
            store.addToCurrentTotals(routeVersionId, page.totalsUntil(dataUntil));
            store.registerVehicle(routeVersionId, vehicleId);
        }
        samples.remove(page.processedUntil(dataUntil).stream().map(DemandSamplePage.PendingSample::id).toList());
        return new Result(page.size(), applicable.size(), page.nextInputId(afterInputId));
    }

    public record Result(int selected, int applied, long nextInputId) {

    }
}
