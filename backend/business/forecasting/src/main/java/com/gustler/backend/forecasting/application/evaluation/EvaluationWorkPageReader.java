package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Restored;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("한 번에 확인할 관측은 1개 이상 100개 이하이다");
        }
        List<Long> ids = List.copyOf(work.page(version, after, through, limit));
        if (ids.isEmpty()) {
            return new Page(0, List.of(), List.of());
        }
        Map<Long, Long> revisions = work.revisions(version, ids);

        Map<Long, PendingEvaluationBuffer.Entry> reusable = new LinkedHashMap<>();
        List<Long> restoreIds = new ArrayList<>();
        for (final long id : ids) {
            PendingEvaluationBuffer.Entry entry = cached.get(id);
            if (!investigating && entry != null
                && entry.key().routeVersionId() == version
                && entry.key().observationId() == id
                && entry.qualityRevision() == qualityRevision
                && Objects.equals(revisions.get(id), entry.workRevision())) {
                reusable.put(id, entry);
                continue;
            }
            restoreIds.add(id);
        }

        List<Restored> restored = List.of();
        if (!restoreIds.isEmpty()) {
            restored = work.restore(version, restoreIds);
        }
        Map<Long, List<Restored>> byObservation = restored.stream()
            .collect(Collectors.groupingBy(row -> row.key().observationId(), LinkedHashMap::new,
                Collectors.toCollection(ArrayList::new)));

        List<Ready> ready = new ArrayList<>();
        for (final long id : ids) {
            PendingEvaluationBuffer.Entry entry = reusable.get(id);
            if (entry != null) {
                ready.add(new Ready(id, qualityRevision, entry.workRevision(), entry.forecasts()));
                continue;
            }
            List<Restored> rows = byObservation.getOrDefault(id, List.of());
            // 상세를 읽지 못한 관측은 완료가 아니라 보류로 남긴다.
            if (rows.isEmpty() || rows.stream().anyMatch(row -> row.forecast().isEmpty())) {
                continue;
            }
            if (!revisions.containsKey(id)) {
                continue;
            }
            ready.add(new Ready(id, qualityRevision, revisions.get(id),
                rows.stream()
                    .map(row -> row.forecast().orElseThrow())
                    .toList()));
        }
        return new Page(ids.getLast(), ids, List.copyOf(ready), revisions);
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
