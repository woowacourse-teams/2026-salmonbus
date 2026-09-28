package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.statistics.DemandRebuildStore.BatchPosition;
import com.gustler.backend.forecasting.application.statistics.DemandRebuildStore.Page;
import com.gustler.backend.forecasting.domain.statistics.DemandSampleRepository;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuildRepository;
import com.gustler.backend.forecasting.domain.statistics.RebuildScanWindow;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 정정 요청을 유지한 채 128행씩 재계산/교체한다. 요청이 남아 있는 동안 해당 누적과 발행은 금지한다. */
@Component
public class DemandStatisticsRebuilder {

    private static final int PAGE_SIZE = DemandStatisticsRebuild.PAGE_SIZE;

    private final DemandStatisticsRebuildRepository rebuilds;
    private final DemandStatisticsRebuildRequests requests;
    private final DemandRebuildStore rebuildStore;
    private final DemandStatisticsStore store;
    private final DemandSampleRepository samples;
    private final RouteDataQualityAccess quality;
    private final Clock clock;

    public DemandStatisticsRebuilder(final DemandStatisticsRebuildRepository rebuilds,
        final DemandStatisticsRebuildRequests requests, final DemandRebuildStore rebuildStore,
        final DemandStatisticsStore store, final DemandSampleRepository samples, final RouteDataQualityAccess quality,
        final Clock clock) {
        this.rebuilds = rebuilds;
        this.requests = requests;
        this.rebuildStore = rebuildStore;
        this.store = store;
        this.samples = samples;
        this.quality = quality;
        this.clock = clock;
    }

    @Transactional(timeout = 2)
    public boolean step(final long routeVersionId, final String vehicleScope) {
        final RebuildScope scope = new RebuildScope(vehicleScope);
        store.limitStatementTime();
        final long currentRevision = quality.lock(routeVersionId);
        // 전체 합계 교체가 끝날 때까지 차량별 중간값을 적용하지 않는다.
        if (!scope.isWholeRoute() && requests.isPending(routeVersionId, RebuildScope.wholeRoute())) {
            return false;
        }
        if (store.qualityRebuildPending(routeVersionId, scope)) {
            return false;
        }
        final Optional<UUID> request = requests.current(routeVersionId, scope);
        if (request.isEmpty()) {
            return false;
        }
        final Optional<DemandStatisticsRebuild> progress = rebuilds.find(routeVersionId, scope);
        if (progress.isEmpty() || !progress.get().serves(request.get(), currentRevision)) {
            // 이전 시도의 중간값을 나눠 정리한 뒤 새 처리 범위를 고정한다.
            if (rebuildStore.clearLeftoverTotals(routeVersionId, scope, PAGE_SIZE) > 0) {
                return true;
            }
            rebuilds.save(DemandStatisticsRebuild.start(routeVersionId, scope, request.get(), currentRevision,
                clock.instant(), samples.lastSampleId(), rebuildStore.lastObservationId(), rebuildStore.lastBatchId()));
            return true;
        }
        final DemandStatisticsRebuild rebuild = progress.get();
        return WorkerOperationLog.measure("statistics_rebuild_" + rebuild.phase().name().toLowerCase(Locale.ROOT),
            routeVersionId, () -> advance(rebuild));
    }

    private boolean advance(final DemandStatisticsRebuild rebuild) {
        final long routeVersionId = rebuild.routeVersionId();
        final RebuildScope scope = rebuild.scope();
        switch (rebuild.phase()) {
            case SCAN -> scan(rebuild);
            case CLEAR -> rebuild.cleared(rebuildStore.clearCurrentTotals(routeVersionId, scope, PAGE_SIZE));
            case COPY -> {
                final Page page = rebuildStore.copyToCurrentTotals(rebuild.requestId(), rebuild.cursorId(), PAGE_SIZE);
                rebuild.copied(page.size(), page.lastId());
            }
            case ACK -> {
                final Page page = rebuildStore.acknowledgeSamples(routeVersionId, scope, rebuild.cursorId(),
                    rebuild.inputUntilId(), rebuild.dataUntil(), PAGE_SIZE);
                rebuild.acknowledged(page.size(), page.lastId());
            }
            case CLEAN -> {
                if (rebuild.cleanedUp(rebuildStore.cleanRebuildTotals(rebuild.requestId(), PAGE_SIZE))) {
                    complete(rebuild);
                    return true;
                }
            }
        }
        rebuilds.save(rebuild);
        return true;
    }

    private void scan(final DemandStatisticsRebuild rebuild) {
        // 배포 전에 시작한 작업은 기존 관측 ID 커서를 끝까지 사용한다.
        if (rebuild.scansByObservationId()) {
            final List<Long> ids = rebuildStore.observationPage(rebuild.cursorId(), rebuild.observationUntilId(),
                PAGE_SIZE);
            rebuildStore.addRebuildTotals(rebuild, ids);
            rebuild.scannedObservations(ids);
            return;
        }
        RebuildScanWindow window = rebuild.scan().orElseThrow();
        if (!window.hasOpenGroup()) {
            final Optional<BatchPosition> end = rebuildStore.nextGroupEnd(rebuild.routeVersionId(), window,
                DemandStatisticsRebuild.BATCH_GROUP_SIZE);
            if (end.isEmpty()) {
                rebuild.noGroupLeft();
                return;
            }
            rebuild.groupOpened(end.get().at(), end.get().id());
            window = rebuild.scan().orElseThrow();
        }
        final List<Long> ids = rebuildStore.groupObservationPage(rebuild.routeVersionId(), window, rebuild.scope(),
            rebuild.cursorId(), rebuild.observationUntilId(), DemandStatisticsRebuild.BATCH_GROUP_SIZE, PAGE_SIZE);
        rebuildStore.addRebuildTotals(rebuild, ids);
        rebuild.scannedGroup(ids);
    }

    private void complete(final DemandStatisticsRebuild rebuild) {
        final long routeVersionId = rebuild.routeVersionId();
        requests.complete(routeVersionId, rebuild.scope(), rebuild.requestId());
        if (rebuild.scope().isWholeRoute()) {
            // 전체 완료와 함께 커밋한다. 중단됐던 차량 계산은 새 합계를 덮어쓰지 않고 다시 시작한다.
            requests.renewVehicleRequests(routeVersionId);
        }
        rebuilds.delete(routeVersionId, rebuild.scope());
        store.recordBaseline(routeVersionId, rebuild.scope().isWholeRoute(), rebuild.dataUntil());
    }
}
