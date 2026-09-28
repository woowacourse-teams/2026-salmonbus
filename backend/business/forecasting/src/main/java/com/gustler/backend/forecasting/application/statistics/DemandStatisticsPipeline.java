package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.statistics.DemandStatisticsPolicy;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsStore.FoldRow;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsPipeline.Step.Status;
import com.gustler.backend.forecasting.domain.statistics.DailyStopDemand;
import com.gustler.backend.forecasting.domain.statistics.DemandSampleStore;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsBaseline;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRunRepository;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.FoldCursor;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.ReduceCursor;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCellTotals;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 한 호출은 한 페이지/단계만 커밋한다. 진행 중인 원합은 예보 조회에 노출하지 않는다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class DemandStatisticsPipeline {

    private static final int PAGE_SIZE = DemandStatisticsRun.PAGE_SIZE;
    private static final String REBUILD = "REBUILD";

    private final DemandStatisticsRunRepository runs;
    private final DemandStatisticsRebuildRequests requests;
    private final DemandStatisticsRebuilder rebuilder;
    private final DemandAccumulator accumulator;
    private final DemandStatisticsStore store;
    private final DemandSampleStore samples;
    private final StopDemandStatisticsRepository statistics;
    private final RouteDataQualityAccess quality;
    private final DemandStatisticsPolicy policy;
    private final Clock clock;

    public DemandStatisticsPipeline(final DemandStatisticsRunRepository runs,
        final DemandStatisticsRebuildRequests requests, final DemandStatisticsRebuilder rebuilder,
        final DemandAccumulator accumulator, final DemandStatisticsStore store, final DemandSampleStore samples,
        final StopDemandStatisticsRepository statistics, final RouteDataQualityAccess quality,
        final DemandStatisticsPolicy policy, final Clock clock) {
        this.runs = runs;
        this.requests = requests;
        this.rebuilder = rebuilder;
        this.accumulator = accumulator;
        this.store = store;
        this.samples = samples;
        this.statistics = statistics;
        this.quality = quality;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional(timeout = 2)
    public Step step(final long routeVersionId) {
        store.limitStatementTime();
        final long currentRevision = WorkerOperationLog.measure("statistics_route_lock", routeVersionId,
            () -> quality.lock(routeVersionId));
        final DemandStatisticsBaseline baseline = store.baseline(routeVersionId);
        if (!baseline.initialized() && !requests.isPending(routeVersionId, RebuildScope.wholeRoute())) {
            requests.request(routeVersionId, RebuildScope.wholeRoute());
        }
        final Optional<RebuildScope> request = requests.next(routeVersionId);
        if (request.isPresent()) {
            runs.find(routeVersionId).ifPresent(run -> {
                run.markStale();
                runs.save(run);
            });
            final boolean advanced = WorkerOperationLog.measure("statistics_rebuild", routeVersionId,
                () -> rebuilder.step(routeVersionId, request.get().vehicleId()));
            return new Step(advanced ? Status.PROGRESSED : Status.WAITING, REBUILD, null);
        }
        final Optional<DemandStatisticsRun> found = runs.find(routeVersionId);
        final Instant now = clock.instant();
        if (found.isPresent() && found.get().isUpToDate(currentRevision, now, policy.refreshInterval())) {
            return new Step(Status.IDLE, "DONE", found.get().dataUntil());
        }
        if (found.isEmpty() || found.get().requiresRestart(currentRevision)) {
            final DemandStatisticsRun run = found.orElseGet(
                () -> DemandStatisticsRun.start(routeVersionId, UUID.randomUUID(), currentRevision, now));
            if (found.isPresent()) {
                run.restart(UUID.randomUUID(), currentRevision, now);
            }
            runs.save(run);
            return new Step(Status.PROGRESSED, "CLEAN", now);
        }
        final DemandStatisticsRun run = found.get();
        final String phase = run.phase().name();
        final Instant dataUntil = run.dataUntil();
        return WorkerOperationLog.measure("statistics_" + phase.toLowerCase(Locale.ROOT), routeVersionId, () -> {
            switch (run.phase()) {
                case CLEAN -> clean(run);
                case CAPTURE -> {
                    return capture(run);
                }
                case ACCUMULATE -> accumulate(run);
                case FOLD -> fold(run);
                case REDUCE -> reduce(run);
                case PUBLISH -> {
                    publish(run);
                    return new Step(Status.COMPLETED, "DONE", dataUntil);
                }
                default -> throw new IllegalStateException("알 수 없는 통계 단계: " + run.phase());
            }
            return new Step(Status.PROGRESSED, phase, dataUntil);
        });
    }

    private void clean(final DemandStatisticsRun run) {
        if (store.clearStagePage(run.routeVersionId(), PAGE_SIZE) > 0) {
            return;
        }
        run.cleaned();
        runs.save(run);
    }

    private Step capture(final DemandStatisticsRun run) {
        final Instant fixed = store.baseline(run.routeVersionId()).fixUntil(run.captureUntil(clock.instant()));
        run.captured(fixed, samples.lastSampleId());
        store.stageCapacities(run.routeVersionId(), run.dataUntil(), run.inputUntilId());
        runs.save(run);
        return new Step(Status.STARTED, "ACCUMULATE", runs.find(run.routeVersionId()).orElseThrow().dataUntil());
    }

    private void accumulate(final DemandStatisticsRun run) {
        final Optional<String> vehicle = store.nextAccumulationVehicle(run.routeVersionId(), run.inputUntilId(),
            run.vehicleCursor(), run.inputCursor());
        if (vehicle.isEmpty()) {
            run.accumulationFinished();
            runs.save(run);
            return;
        }
        final DemandAccumulator.Result result = accumulator.apply(run.routeVersionId(), vehicle.get(),
            run.accumulationStartAfter(vehicle.get()), run.inputUntilId(), run.dataUntil());
        if (result.waitingForRebuild()) {
            return;
        }
        run.accumulated(vehicle.get(), result.nextInputId());
        runs.save(run);
    }

    private void fold(final DemandStatisticsRun run) {
        final List<FoldRow> rows = store.foldPage(run.routeVersionId(), run.foldCursor(), PAGE_SIZE);
        final List<DailyStopDemand> days = rows.stream().filter(row -> row.capacity() != null)
            .map(row -> row.total().onDay(row.capacity(), clock)).toList();
        store.addToDayStage(run.routeVersionId(), days);
        if (rows.size() < PAGE_SIZE) {
            run.foldFinished();
        } else {
            final FoldRow last = rows.getLast();
            run.folded(new FoldCursor(last.total().vehicleId(), last.total().arrivedHourStart(),
                last.total().targetStopOrder()));
        }
        runs.save(run);
    }

    private void reduce(final DemandStatisticsRun run) {
        final List<DailyStopDemand> days = store.reducePage(run.routeVersionId(), run.reduceCursor(), PAGE_SIZE);
        store.addToCellStage(run.routeVersionId(), days.stream().map(StopDemandCellTotals::ofDay).toList());
        if (days.size() < PAGE_SIZE) {
            run.reduceFinished();
        } else {
            final DailyStopDemand last = days.getLast();
            run.reduced(new ReduceCursor(last.stopOrder(), last.timeSlot(), last.arrivalDate()));
        }
        runs.save(run);
    }

    private void publish(final DemandStatisticsRun run) {
        final String calculationVersion = DemandStatisticsVersion.CURRENT_CALCULATION_VERSION;
        final int revision = Math.incrementExact(statistics.currentRevision(run.routeVersionId(), calculationVersion));
        final Instant computedAt = clock.instant();
        statistics.append(new DemandStatisticsVersion(run.routeVersionId(), calculationVersion, revision,
            run.dataUntil(), computedAt,
            store.stagedCells(run.routeVersionId()).stream().map(StopDemandCellTotals::toMeasurement).toList()));
        run.published(computedAt);
        runs.save(run);
    }

    public record Step(Status status, String phase, Instant dataUntil) {

        public enum Status { IDLE, WAITING, PROGRESSED, STARTED, COMPLETED }
    }
}
