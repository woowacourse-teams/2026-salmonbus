package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.statistics.DemandStatisticsPolicy;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsPipeline.Step;
import com.gustler.backend.forecasting.domain.statistics.DailyStopDemand;
import com.gustler.backend.forecasting.domain.statistics.DemandSampleStore;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsBaseline;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRunRepository;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 한 호출은 한 페이지/단계만 커밋한다. 진행 중인 원합은 예보 조회에 노출하지 않는다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class DemandStatisticsPipeline {
    private static final int PAGE_SIZE = DemandStatisticsRun.PAGE_SIZE;

    private final DemandStatisticsRunRepository runs;
    private final DemandAccumulator accumulator;
    private final DemandStatisticsStore store;
    private final DemandSampleStore samples;
    private final StopDemandStatisticsRepository statistics;
    private final RouteWorkAccess routeWork;
    private final DemandStatisticsPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public DemandStatisticsPipeline(
        DemandStatisticsRunRepository runs,
        DemandAccumulator accumulator,
        DemandStatisticsStore store,
        DemandSampleStore samples,
        StopDemandStatisticsRepository statistics,
        RouteWorkAccess routeWork,
        DemandStatisticsPolicy policy,
        Clock clock,
        PlatformTransactionManager transactions
    ) {
        this.runs = runs;
        this.accumulator = accumulator;
        this.store = store;
        this.samples = samples;
        this.statistics = statistics;
        this.routeWork = routeWork;
        this.policy = policy;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(2);
    }

    /**
     * 노선 통계의 한 처리 단계를 실행한다. 외부 아카이브 전송은 기존 DB 트랜잭션 밖에서만 시도한다.
     */
    public Step step(final long routeVersionId) {
        Optional<Instant> idle = transaction.execute(status -> {
            store.limitStatementTime();
            return store.idleDataUntil(routeVersionId, clock.instant().minus(policy.refreshInterval()));
        });
        if (idle.isPresent()) {
            return new Step(Step.Status.IDLE, "DONE", idle.get());
        }

        return transaction.execute(status -> stepInTransaction(routeVersionId));
    }

    private Step stepInTransaction(final long routeVersionId) {
        store.limitStatementTime();
        final long currentRevision = WorkerOperationLog.measure("statistics_route_lock", routeVersionId,
            () -> routeWork.lock(routeVersionId));
        DemandStatisticsBaseline baseline = store.baseline(routeVersionId);
        if (!baseline.initialized()) {
            throw new IllegalStateException("기존 수요 통계의 초기 기준 인계가 필요하다");
        }
        Optional<DemandStatisticsRun> found = runs.find(routeVersionId);
        Instant now = clock.instant();
        if (found.isPresent() && found.get().isUpToDate(currentRevision, now, policy.refreshInterval())) {
            return new Step(Step.Status.IDLE, "DONE", found.get().dataUntil());
        }
        if (found.isEmpty() || found.get().requiresRestart(currentRevision)) {
            DemandStatisticsRun run = found
                .orElseGet(() -> DemandStatisticsRun.start(routeVersionId, UUID.randomUUID(), currentRevision, now));
            if (found.isPresent()) {
                run.restart(UUID.randomUUID(), currentRevision, now);
            }
            runs.save(run);
            return new Step(Step.Status.PROGRESSED, "CLEAN", now);
        }
        DemandStatisticsRun run = found.get();
        String phase = run.phase().name();
        Instant dataUntil = run.dataUntil();
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
                return new Step(Step.Status.COMPLETED, "DONE", dataUntil);
            }
            default -> throw new IllegalStateException("알 수 없는 통계 단계: " + run.phase());
            }
            return new Step(Step.Status.PROGRESSED, phase, dataUntil);
        });
    }

    private void clean(DemandStatisticsRun run) {
        if (store.clearStagePage(run.routeVersionId(), PAGE_SIZE) > 0) {
            return;
        }
        run.cleaned();
        runs.save(run);
    }

    private Step capture(DemandStatisticsRun run) {
        Instant fixed = store.baseline(run.routeVersionId()).fixUntil(run.captureUntil(clock.instant()));
        run.captured(fixed, samples.lastSampleId());
        store.stageCapacities(run.routeVersionId(), run.dataUntil(), run.inputUntilId());
        runs.save(run);
        return new Step(Step.Status.STARTED, "ACCUMULATE", runs.find(run.routeVersionId()).orElseThrow().dataUntil());
    }

    private void accumulate(DemandStatisticsRun run) {
        Optional<String> vehicle = store.nextAccumulationVehicle(run.routeVersionId(), run.inputUntilId(),
            run.vehicleCursor(), run.inputCursor());
        if (vehicle.isEmpty()) {
            run.accumulationFinished();
            runs.save(run);
            return;
        }
        DemandAccumulator.Result result = accumulator.apply(run.routeVersionId(), vehicle.get(),
            run.accumulationStartAfter(vehicle.get()), run.inputUntilId(), run.dataUntil());
        run.accumulated(vehicle.get(), result.nextInputId());
        runs.save(run);
    }

    private void fold(DemandStatisticsRun run) {
        List<DemandStatisticsStore.FoldRow> rows = store.foldPage(run.routeVersionId(), run.foldCursor(), PAGE_SIZE);
        List<DailyStopDemand> days = rows.stream().filter(row -> row.capacity() != null)
            .map(row -> row.total().onDay(row.capacity(), clock)).toList();
        store.addToDayStage(run.routeVersionId(), days);
        advanceFold(run, rows);
        runs.save(run);
    }

    private void reduce(DemandStatisticsRun run) {
        List<DailyStopDemand> days = store.reducePage(run.routeVersionId(), run.reduceCursor(), PAGE_SIZE);
        store.addToCellStage(run.routeVersionId(), days.stream().map(StopDemandCellTotals::ofDay).toList());
        advanceReduce(run, days);
        runs.save(run);
    }

    private void publish(DemandStatisticsRun run) {
        String calculationVersion = DemandStatisticsVersion.CURRENT_CALCULATION_VERSION;
        final int revision = Math.incrementExact(statistics.currentRevision(run.routeVersionId(), calculationVersion));
        Instant computedAt = clock.instant();
        statistics.append(
            new DemandStatisticsVersion(run.routeVersionId(), calculationVersion, revision, run.dataUntil(), computedAt,
                store.stagedCells(run.routeVersionId()).stream().map(StopDemandCellTotals::toMeasurement).toList()));
        run.published(computedAt);
        runs.save(run);
    }

    public record Step(Step.Status status, String phase, Instant dataUntil) {
        public enum Status {
            IDLE, WAITING, PROGRESSED, STARTED, COMPLETED
        }
    }

    private static void advanceFold(DemandStatisticsRun run, List<DemandStatisticsStore.FoldRow> rows) {
        if (rows.size() < PAGE_SIZE) {
            run.foldFinished();
            return;
        }
        DemandStatisticsStore.FoldRow last = rows.getLast();
        run.folded(new DemandStatisticsRun.FoldCursor(last.total().vehicleId(), last.total().arrivedHourStart(),
            last.total().targetStopOrder()));
    }

    private static void advanceReduce(DemandStatisticsRun run, List<DailyStopDemand> days) {
        if (days.size() < PAGE_SIZE) {
            run.reduceFinished();
            return;
        }
        DailyStopDemand last = days.getLast();
        run.reduced(new DemandStatisticsRun.ReduceCursor(last.stopOrder(), last.timeSlot(), last.arrivalDate()));
    }
}
