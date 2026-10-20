package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.application.evaluation.EvaluateForecastsService.RouteInput;
import com.gustler.backend.forecasting.application.evaluation.EvaluationSelection.Cursor;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkPageReader.Ready;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Key;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 한 노선 버전의 정산·통계 표본·당일 합계·작업 종료를 함께 확정한다. */
@Component
public class EvaluationRouteCommitter {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvaluationRouteCommitter.class);
    private final EvaluationWorkStore work;
    private final RouteDataQualityAccess quality;
    private final ForecastEvaluationWriter writer;
    private final EvaluationQualityRecheck qualityRecheck;

    public EvaluationRouteCommitter(
        EvaluationWorkStore work,
        RouteDataQualityAccess quality,
        ForecastEvaluationWriter writer,
        EvaluationQualityRecheck qualityRecheck
    ) {
        this.work = work;
        this.quality = quality;
        this.writer = writer;
        this.qualityRecheck = qualityRecheck;
    }

    /**
     * 실제 트랜잭션에서 작업·품질 판본을 확인하고 평가를 확정한다. 메모리 완료 통지는 커밋 뒤에 실행한다.
     */
    @Transactional(readOnly = false)
    public void commit(RouteInput input, List<ForecastEvaluation> results,
        BiConsumer<Integer, Change> completion) {
        commit(input, results, completion, false);
    }

    /**
     * 실제 트랜잭션에서 작업·품질 판본을 확인하고 평가를 확정한다. 메모리 완료 통지는 커밋 뒤에 실행한다.
     */
    @Transactional(readOnly = false)
    public void commit(
        RouteInput input,
        List<ForecastEvaluation> results,
        BiConsumer<Integer, Change> completion,
        final boolean memoryChanged
    ) {
        requireTransaction();
        final long actualRevision = quality.lock(input.version());
        if (!workUnchanged(input)) {
            LOGGER.info("event=settlement_skipped reason=WORK_CHANGED routeVersionId={}", input.version());
            return;
        }

        final boolean qualityChanged = memoryChanged || actualRevision != input.revision();
        List<ForecastEvaluation> verified = verifyIfQualityChanged(input, results, qualityChanged);
        if (qualityChanged) {
            LOGGER.info("event=settlement_quality_rechecked routeVersionId={} calculated={} unchanged={} deferred={}",
                input.version(), results.size(), verified.size(), results.size() - verified.size());
        }

        Set<Key> completed = new HashSet<>();
        for (List<Long> ids : chunks(input.checked())) {
            work.closeExcluded(input.version(), ids, 500);
            completed.addAll(work.completed(input.version(), ids));
        }
        Set<Key> selected = input.selected()
            .stream()
            .map(forecast -> new Key(forecast.vehicleObservationId(), forecast.targetStopOrder()))
            .collect(Collectors.toCollection(HashSet::new));
        List<ForecastEvaluation> pending = verified.stream()
            .filter(result -> {
                Key key = new Key(result.vehicleObservationId(), result.targetStopOrder());
                return selected.contains(key) && !completed.contains(key);
            })
            .toList();
        writer.complete(pending);
        for (List<Long> ids : chunks(input.checked())) {
            completed.addAll(work.completed(input.version(), ids));
            work.releaseCompleted(input.version(), ids);
        }

        List<Ready> remaining = new ArrayList<>();
        // 변경 전에 읽은 대기 상세는 다음 회차에서 DB로 다시 확인한다.
        if (!input.investigating() && !qualityChanged) {
            for (Ready item : input.ready()) {
                List<PendingForecast> forecasts = item.forecasts()
                    .stream()
                    .filter(forecast -> !completed.contains(new Key(
                        forecast.vehicleObservationId(), forecast.targetStopOrder())))
                    .toList();
                if (!forecasts.isEmpty()) {
                    remaining.add(new Ready(
                        item.observationId(), input.revision(), item.workRevision(), forecasts));
                }
            }
        }
        Change change = new Change(input.version(), input.cursor(), Set.copyOf(input.checked()),
            List.copyOf(remaining));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(final int status) {
                completion.accept(status, change);
            }
        });
    }

    private boolean workUnchanged(RouteInput input) {
        Map<Long, Long> current = new LinkedHashMap<>();
        for (List<Long> ids : chunks(input.checked())) {
            current.putAll(work.revisions(input.version(), ids));
        }
        return current.size() == input.checked().size() && current.equals(input.workRevisions());
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> chunks = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += 100) {
            chunks.add(ids.subList(from, Math.min(from + 100, ids.size())));
        }
        return chunks;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("노선별 정산 확정에는 실제 트랜잭션이 필요하다");
        }
    }

    public record Change(long version, Cursor cursor, Set<Long> checked,
        List<Ready> remaining) {
        /** 원본 참조와 분리하고 기존 null·원소·순서를 보존한다. */
        public Change {
            if (checked != null) {
                checked = Collections.unmodifiableSet(new LinkedHashSet<>(checked));
            }
            if (remaining != null) {
                remaining = Collections.unmodifiableList(new ArrayList<>(remaining));
            }
        }
    }

    private List<ForecastEvaluation> verifyIfQualityChanged(
        RouteInput input,
        List<ForecastEvaluation> results,
        final boolean qualityChanged
    ) {
        if (qualityChanged) {
            return WorkerOperationLog.measure("settlement_quality_recheck", input.version(),
                () -> qualityRecheck.unchanged(input, results));
        }
        return results;
    }
}
