package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.evaluation.EvaluateForecasts;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.quality.WorkerQualityState;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabelResolver;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.annotation.Propagation;

@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class EvaluateForecastsService implements EvaluateForecasts {
    private static final Logger log = LoggerFactory.getLogger(EvaluateForecastsService.class);
    private final EvaluationWorkStore work;
    private final EvaluationWorkPageReader pages;
    private final EvaluationRouteCommitter committer;
    private final ArrivalObservationQuery arrivals;
    private final RecentArrivalObservationCache observationCache;
    private final RouteDataQualityAccess quality;
    private final ForecastPolicy policy;
    private final Clock clock;
    private final PendingEvaluationBuffer buffer;
    private final int pageLimit;
    private final Map<Long, EvaluationSelection.Cursor> cursors = new LinkedHashMap<>();
    private long memoryRevision;
    private WorkerQualityState qualityMemory;

    @Autowired(required = false)
    public void useQualityMemory(WorkerQualityState qualityMemory) { this.qualityMemory = qualityMemory; }

    public EvaluateForecastsService(
        EvaluationWorkStore work,
        EvaluationWorkPageReader pages,
        EvaluationRouteCommitter committer,
        ArrivalObservationQuery arrivals,
        RouteDataQualityAccess quality,
        ForecastPolicy policy,
        Clock clock,
        @Value("${forecast.evaluation.memory-limit:60000}") int memoryLimit,
        @Value("${forecast.evaluation.recovery-page-limit:3}") int pageLimit,
        RecentArrivalObservationCache observationCache
    ) {
        if (pageLimit < 1) throw new IllegalArgumentException("복원 페이지 한도는 양수여야 한다");
        this.work = work;
        this.pages = pages;
        this.committer = committer;
        this.arrivals = arrivals;
        this.observationCache = observationCache;
        this.quality = quality;
        this.policy = policy;
        this.clock = clock;
        this.buffer = new PendingEvaluationBuffer(memoryLimit);
        this.pageLimit = pageLimit;
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void settleArrivalLabels() {
        WorkerOperationLog.run("settlement_tick", "all", this::settleOnce);
    }

    private void settleOnce() {
        Instant now = clock.instant();
        Snapshot beforeRoutes = snapshot();
        List<Long> versions = WorkerOperationLog.measure("settlement_pending_routes", "all", work::routes);
        discardInactive(beforeRoutes, versions);
        for (long version : versions) {
            Snapshot snapshot = snapshot();
            var observations = observationCache.begin(now);
            Optional<WorkerQualityState.Read> remembered = rememberedQuality(version);
            long revision = remembered.map(value -> value.snapshot().revision())
                .orElseGet(() -> quality.current(version));
            // 상태가 불확실하면 기존 DB 조회를 사용하고 관측·대기 캐시 재사용도 차단한다.
            boolean investigating = remembered.map(value -> value.snapshot().anyInvestigationPending())
                .orElseGet(() -> qualityMemory != null || quality.anyInvestigationPending(version));
            RouteInput input = read(version, revision, snapshot, investigating);
            List<ForecastEvaluation> results = evaluate(input, observations, now);
            boolean memoryChanged = remembered.isPresent() && !qualityMemory.stillCurrent(remembered.get());
            committer.commit(input, results, (status, change) -> {
                apply(snapshot, status, change);
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    try {
                        observations.committed(clock.instant());
                    } catch (RuntimeException failure) {
                        log.warn("event=arrival_cache_publish_failed exceptionType={}",
                            failure.getClass().getSimpleName());
                    }
                }
            }, memoryChanged);
        }
    }

    private Optional<WorkerQualityState.Read> rememberedQuality(long version) {
        if (qualityMemory == null) return Optional.empty();
        try {
            return qualityMemory.read(version);
        } catch (RuntimeException failure) {
            log.warn("event=quality_memory_read_failed routeVersionId={} exceptionType={}",
                version, failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private RouteInput read(long version, long revision,
        Snapshot snapshot, boolean investigating
    ) {
        EvaluationSelection.Cursor cursor = snapshot.cursors().getOrDefault(
            version, EvaluationSelection.Cursor.START);
        if (cursor.through() == 0) {
            cursor = new EvaluationSelection.Cursor(work.lastObservationId(version), 0, 0, 0);
        }
        Map<Long, PendingEvaluationBuffer.Entry> cached = new LinkedHashMap<>();
        for (var entry : snapshot.entries()) {
            if (entry.key().routeVersionId() == version) cached.put(entry.key().observationId(), entry);
        }
        Set<Long> checked = new LinkedHashSet<>();
        Map<Long, Long> workRevisions = new LinkedHashMap<>();
        Map<Long, EvaluationWorkPageReader.Ready> ready = new LinkedHashMap<>();
        List<PendingForecast> selected = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < pageLimit && cursor.through() != 0
                    && selected.size() < policy.pendingLimit(); pageNumber++) {
            long after = cursor.after();
            long through = cursor.through();
            var page = WorkerOperationLog.measure("settlement_pending_forecasts", version,
                () -> pages.read(version, after, through, 100, revision, investigating, cached));
            checked.addAll(page.checkedIds());
            workRevisions.putAll(page.workRevisions());
            for (var item : page.ready()) ready.put(item.observationId(), item);
            var selection = EvaluationSelection.take(cursor, page, policy.pendingLimit() - selected.size());
            selected.addAll(selection.forecasts());
            cursor = selection.next();
        }
        return new RouteInput(version, revision, investigating, cursor, List.copyOf(checked),
            Map.copyOf(workRevisions), List.copyOf(ready.values()), List.copyOf(selected));
    }

    private List<ForecastEvaluation> evaluate(RouteInput input,
        RecentArrivalObservationCache.Round observations, Instant now) {
        long version = input.version();
        List<PendingForecast> forecasts = input.selected();
        Map<String, List<PendingForecast>> byVehicle = new LinkedHashMap<>();
        for (var forecast : forecasts) {
            byVehicle.computeIfAbsent(forecast.vehicleId(), ignored -> new ArrayList<>()).add(forecast);
        }
        List<ForecastEvaluation> results = new ArrayList<>();
        for (var vehicle : byVehicle.entrySet()) {
            Instant earliest = vehicle.getValue()
                .stream()
                .map(PendingForecast::generatedAt)
                .min(Instant::compareTo)
                .orElseThrow();
            List<ArrivalCandidate> candidates = vehicle.getKey() == null ? List.of()
                : WorkerOperationLog.measure("settlement_arrival_candidates", version,
                    () -> observations.read(arrivals, version, input.revision(), input.investigating(), vehicle.getKey(), earliest, policy.arrivalLimit()));
            for (PendingForecast forecast : vehicle.getValue()) {
                ArrivalLabel label = ArrivalLabelResolver.resolve(forecast, candidates, now);
                if (label.settles()) results.add(ForecastEvaluation.completed(
                    forecast.vehicleObservationId(), forecast.targetStopOrder(), label, now));
            }
        }
        return results;
    }

    private synchronized Snapshot snapshot() {
        return new Snapshot(memoryRevision, Map.copyOf(cursors), buffer.snapshot());
    }

    private synchronized void discardInactive(Snapshot snapshot, List<Long> versions) {
        if (snapshot.revision() != memoryRevision) return;
        Set<Long> active = Set.copyOf(versions);
        boolean changed = cursors.keySet().removeIf(version -> !active.contains(version));
        for (var entry : buffer.snapshot()) {
            if (!active.contains(entry.key().routeVersionId())) {
                buffer.invalidate(entry);
                changed = true;
            }
        }
        if (changed) memoryRevision++;
    }

    private synchronized void apply(Snapshot snapshot, int status, EvaluationRouteCommitter.Change change) {
        if (status == TransactionSynchronization.STATUS_ROLLED_BACK
                    || snapshot.revision() != memoryRevision) return;
        memoryRevision++;
        if (status != TransactionSynchronization.STATUS_COMMITTED) {
            resetMemory();
            log.warn("event=evaluation_memory_reset reason=UNKNOWN_TRANSACTION_RESULT");
            return;
        }
        try {
            for (var entry : buffer.snapshot()) {
                if (entry.key().routeVersionId() == change.version()
                    && change.checked().contains(entry.key().observationId())) {
                    buffer.invalidate(entry);
                }
            }
            cursors.put(change.version(), change.cursor());
            for (var item : change.remaining()) {
                while (!buffer.restore(item.qualityRevision(), item.workRevision(), item.forecasts())) {
                    buffer.evictOldest();
                }
            }
        } catch (RuntimeException failure) {
            resetMemory();
            log.warn("event=evaluation_memory_reset reason=AFTER_COMMIT_FAILED exceptionType={}",
                failure.getClass().getSimpleName());
        }
    }

    private void resetMemory() {
        buffer.clear();
        cursors.clear();
    }

    private record Snapshot(long revision, Map<Long, EvaluationSelection.Cursor> cursors,
        List<PendingEvaluationBuffer.Entry> entries) {
    }

    record RouteInput(long version, long revision, boolean investigating,
        EvaluationSelection.Cursor cursor, List<Long> checked, Map<Long, Long> workRevisions,
        List<EvaluationWorkPageReader.Ready> ready, List<PendingForecast> selected) { }
}
