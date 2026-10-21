package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Restored;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 품질·작업 판본이 같은 메모리 상세만 재사용하고 나머지는 DB에서 복원한다.
 * 읽지 못한 상세를 완료로 간주하지 않는다.
 */
@Component
@Transactional(readOnly = true)
class EvaluationWorkPageReader {
    private final EvaluationWorkStore work;

    EvaluationWorkPageReader(EvaluationWorkStore work) {
        this.work = work;
    }

    Page read(
        final long version,
        final long after,
        final long through,
        final int limit,
        final long qualityRevision,
        final boolean investigating,
        Map<Long, PendingEvaluationBuffer.Entry> cached
    ) {
        validatePageLimit(limit);
        List<Long> ids = List.copyOf(work.page(version, after, through, limit));
        if (ids.isEmpty()) {
            return new Page(0, List.of(), List.of());
        }
        Map<Long, Long> revisions = work.revisions(version, ids);
        Map<Long, PendingEvaluationBuffer.Entry> reusableEntries = findReusableEntries(
            ids, cached, revisions, version, qualityRevision, investigating);
        List<Long> observationIdsToRestore = findObservationIdsToRestore(ids, reusableEntries);
        Map<Long, List<Restored>> restoredForecastsByObservationId =
            restoreForecastsByObservationId(version, observationIdsToRestore);
        List<Ready> readyEvaluations = createReadyEvaluations(
            ids, reusableEntries, restoredForecastsByObservationId, revisions, qualityRevision);
        return new Page(ids.getLast(), ids, readyEvaluations, revisions);
    }

    private static void validatePageLimit(final int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("한 번에 확인할 관측은 1개 이상 100개 이하이다");
        }
    }

    private static Map<Long, PendingEvaluationBuffer.Entry> findReusableEntries(
        List<Long> ids,
        Map<Long, PendingEvaluationBuffer.Entry> cached,
        Map<Long, Long> revisions,
        final long version,
        final long qualityRevision,
        final boolean investigating
    ) {
        Map<Long, PendingEvaluationBuffer.Entry> reusableEntries = new LinkedHashMap<>();
        for (final long id : ids) {
            PendingEvaluationBuffer.Entry entry = cached.get(id);
            if (canReuseCachedEntry(entry, revisions.get(id), version, id, qualityRevision, investigating)) {
                reusableEntries.put(id, entry);
            }
        }
        return reusableEntries;
    }

    private static boolean canReuseCachedEntry(
        PendingEvaluationBuffer.Entry entry,
        Long workRevision,
        final long version,
        final long observationId,
        final long qualityRevision,
        final boolean investigating
    ) {
        return !investigating && entry != null
            && entry.key().routeVersionId() == version
            && entry.key().observationId() == observationId
            && entry.qualityRevision() == qualityRevision
            && Objects.equals(workRevision, entry.workRevision());
    }

    private static List<Long> findObservationIdsToRestore(
        List<Long> ids,
        Map<Long, PendingEvaluationBuffer.Entry> reusableEntries
    ) {
        return ids.stream()
            .filter(id -> !reusableEntries.containsKey(id))
            .toList();
    }

    private Map<Long, List<Restored>> restoreForecastsByObservationId(
        final long version,
        List<Long> observationIds
    ) {
        if (observationIds.isEmpty()) {
            return Map.of();
        }
        return work.restore(version, observationIds)
            .stream()
            .collect(Collectors.groupingBy(row -> row.key().observationId(), LinkedHashMap::new,
                Collectors.toCollection(ArrayList::new)));
    }

    private static List<Ready> createReadyEvaluations(
        List<Long> ids,
        Map<Long, PendingEvaluationBuffer.Entry> reusableEntries,
        Map<Long, List<Restored>> restoredForecastsByObservationId,
        Map<Long, Long> revisions,
        final long qualityRevision
    ) {
        List<Ready> readyEvaluations = new ArrayList<>();
        for (final long id : ids) {
            createReadyEvaluation(id, qualityRevision, reusableEntries.get(id),
                restoredForecastsByObservationId.getOrDefault(id, List.of()), revisions.get(id))
                .ifPresent(readyEvaluations::add);
        }
        return List.copyOf(readyEvaluations);
    }

    private static Optional<Ready> createReadyEvaluation(
        final long observationId,
        final long qualityRevision,
        PendingEvaluationBuffer.Entry reusableEntry,
        List<Restored> restoredRows,
        Long workRevision
    ) {
        if (reusableEntry != null) {
            return Optional.of(new Ready(observationId, qualityRevision, reusableEntry.workRevision(),
                reusableEntry.forecasts()));
        }
        if (!hasCompleteForecasts(restoredRows) || workRevision == null) {
            return Optional.empty();
        }
        List<PendingForecast> forecasts = restoredRows.stream()
            .map(row -> row.forecast().orElseThrow())
            .toList();
        return Optional.of(new Ready(observationId, qualityRevision, workRevision, forecasts));
    }

    private static boolean hasCompleteForecasts(List<Restored> restoredRows) {
        return !restoredRows.isEmpty() && restoredRows.stream().allMatch(row -> row.forecast().isPresent());
    }

    record Ready(long observationId, long qualityRevision, long workRevision, List<PendingForecast> forecasts) {
        Ready(final long observationId, final long qualityRevision, List<PendingForecast> forecasts) {
            this(observationId, qualityRevision, 1, forecasts);
        }

        Ready {
            forecasts = List.copyOf(forecasts);
        }
    }

    record Page(long nextAfter, List<Long> checkedIds, List<Ready> ready, Map<Long, Long> workRevisions) {
        Page(final long nextAfter, List<Long> checkedIds, List<Ready> ready) {
            this(nextAfter, checkedIds, ready, Map.of());
        }

        Page {
            workRevisions = Map.copyOf(workRevisions);
            checkedIds = List.copyOf(checkedIds);
            ready = List.copyOf(ready);
        }
    }
}
