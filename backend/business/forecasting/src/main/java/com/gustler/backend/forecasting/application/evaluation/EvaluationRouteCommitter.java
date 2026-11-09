package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
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
    private final RouteWorkAccess routeWork;
    private final ForecastEvaluationWriter writer;
    private final EvaluationInputRecheck inputRecheck;

    public EvaluationRouteCommitter(
        EvaluationWorkStore work,
        RouteWorkAccess routeWork,
        ForecastEvaluationWriter writer,
        EvaluationInputRecheck inputRecheck
    ) {
        this.work = work;
        this.routeWork = routeWork;
        this.writer = writer;
        this.inputRecheck = inputRecheck;
    }

    /**
     * 실제 트랜잭션에서 작업·입력 판본을 확인하고 평가를 확정한다. 메모리 완료 통지는 커밋 뒤에 실행한다.
     */
    @Transactional(readOnly = false)
    public void commit(EvaluateForecastsService.RouteInput input, List<ForecastEvaluation> results,
        BiConsumer<Integer, Change> completion) {
        requireTransaction();
        final long actualRevision = routeWork.lock(input.version());
        if (!workUnchanged(input)) {
            LOGGER.info("event=settlement_skipped reason=WORK_CHANGED routeVersionId={}", input.version());
            return;
        }

        final boolean inputChanged = actualRevision != input.revision();
        List<ForecastEvaluation> verified = verifyIfInputChanged(input, results, inputChanged);
        if (inputChanged) {
            LOGGER.info("event=settlement_input_rechecked routeVersionId={} calculated={} unchanged={} deferred={}",
                input.version(), results.size(), verified.size(), results.size() - verified.size());
        }

        Set<EvaluationWorkStore.Key> completed = new HashSet<>();
        for (List<Long> ids : chunks(input.checked())) {
            completed.addAll(work.completed(input.version(), ids));
        }
        Set<EvaluationWorkStore.Key> selected = input.selected().stream()
            .map(forecast -> new EvaluationWorkStore.Key(forecast.vehicleObservationId(), forecast.targetStopOrder()))
            .collect(Collectors.toCollection(HashSet::new));
        List<ForecastEvaluation> pending = verified.stream().filter(result -> {
            EvaluationWorkStore.Key key = new EvaluationWorkStore.Key(result.vehicleObservationId(),
                result.targetStopOrder());
            return selected.contains(key) && !completed.contains(key);
        }).toList();
        writer.complete(pending);
        for (List<Long> ids : chunks(input.checked())) {
            completed.addAll(work.completed(input.version(), ids));
            work.releaseCompleted(input.version(), ids);
        }

        List<EvaluationWorkPageReader.Ready> remaining = new ArrayList<>();
        // 변경 전에 읽은 대기 상세는 다음 회차에서 DB로 다시 확인한다.
        if (!inputChanged) {
            for (EvaluationWorkPageReader.Ready item : input.ready()) {
                List<PendingForecast> forecasts = item.forecasts().stream()
                    .filter(forecast -> !completed.contains(
                        new EvaluationWorkStore.Key(forecast.vehicleObservationId(), forecast.targetStopOrder())))
                    .toList();
                if (!forecasts.isEmpty()) {
                    remaining.add(new EvaluationWorkPageReader.Ready(item.observationId(), input.revision(),
                        item.workRevision(), forecasts));
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

    private boolean workUnchanged(EvaluateForecastsService.RouteInput input) {
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

    public record Change(long version, EvaluationSelection.Cursor cursor, Set<Long> checked,
        List<EvaluationWorkPageReader.Ready> remaining) {
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

    private List<ForecastEvaluation> verifyIfInputChanged(EvaluateForecastsService.RouteInput input,
        List<ForecastEvaluation> results, final boolean inputChanged) {
        if (inputChanged) {
            return WorkerOperationLog.measure("settlement_quality_recheck", input.version(),
                () -> inputRecheck.unchanged(input, results));
        }
        return results;
    }
}
