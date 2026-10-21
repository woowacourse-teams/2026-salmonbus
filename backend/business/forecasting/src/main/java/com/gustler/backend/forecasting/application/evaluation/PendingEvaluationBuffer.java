package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** DB 작업 기록에서 복원 가능한 대기만 제한된 개수로 보관한다. */
public final class PendingEvaluationBuffer {
    private final int maxForecasts;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();
    private int size;

    public PendingEvaluationBuffer(final int maxForecasts) {
        if (maxForecasts < 12) {
            throw new IllegalArgumentException("한 관측의 최대 예보 묶음을 보관할 수 있어야 한다");
        }
        this.maxForecasts = maxForecasts;
    }

    /**
     * 동일 관측의 중복 없는 예측 1~12개를 복원한다. 전체 용량을 넘으면 변경 없이 false를 반환하고 잘못된 묶음은 예외로 거부한다.
     */
    public synchronized boolean restore(final long qualityRevision, List<PendingForecast> forecasts) {
        return restore(qualityRevision, 1, forecasts);
    }

    /**
     * 동일 관측의 중복 없는 예측 1~12개를 복원한다. 전체 용량을 넘으면 변경 없이 false를 반환하고 잘못된 묶음은 예외로 거부한다.
     */
    public synchronized boolean restore(
        final long qualityRevision,
        final long workRevision,
        List<PendingForecast> forecasts
    ) {
        if (hasInvalidForecastCount(forecasts)) {
            throw new IllegalArgumentException("복원할 관측의 미완료 예보가 필요하다");
        }

        List<PendingForecast> copy = List.copyOf(forecasts);
        Key key = keyOf(copy.getFirst());
        Set<Integer> targets = new HashSet<>();
        for (PendingForecast forecast : copy) {
            if (!key.equals(keyOf(forecast)) || !targets.add(forecast.targetStopOrder())) {
                throw new IllegalArgumentException("같은 관측의 중복 없는 예보만 복원한다");
            }
        }
        Entry old = entries.get(key);
        final int after = size - (forecastCountOf(old)) + copy.size();
        if (after > maxForecasts) {
            return false;
        }
        entries.put(key, new Entry(key, qualityRevision, workRevision, copy));
        size = after;
        return true;
    }

    private boolean hasInvalidForecastCount(List<PendingForecast> forecasts) {
        if (forecasts == null || forecasts.isEmpty()) {
            return true;
        }
        return forecasts.size() > 12;
    }

    /** 평가 순서를 순환한다. 반환 자체로 대기를 완료하거나 삭제하지 않는다. */
    public synchronized List<Entry> next(final int maxObservations) {
        if (maxObservations < 1) {
            throw new IllegalArgumentException("평가 관측 한도는 양수여야 한다");
        }
        List<Entry> selected = new ArrayList<>();
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext() && selected.size() < maxObservations) {
            selected.add(iterator.next().getValue());
            iterator.remove();
        }
        for (Entry entry : selected) {
            entries.put(entry.key, entry);
        }
        return List.copyOf(selected);
    }

    /** 실제 DB 커밋 후, 해당 관측에서 완료가 확인된 대상만 제거한다. */
    public synchronized void committed(Entry evaluated, Set<Integer> completedTargets) {
        if (entries.get(evaluated.key) != evaluated) {
            return;
        }
        List<PendingForecast> remaining = evaluated.forecasts.stream()
            .filter(forecast -> !completedTargets.contains(forecast.targetStopOrder()))
            .toList();
        if (remaining.size() == evaluated.forecasts.size()) {
            return;
        }
        entries.remove(evaluated.key);
        size -= evaluated.forecasts.size() - remaining.size();
        if (!remaining.isEmpty()) {
            entries.put(evaluated.key,
                new Entry(evaluated.key, evaluated.qualityRevision, evaluated.workRevision, remaining));
        }
    }

    /** 품질 변경·DB 재확인 필요 시 사용한다. DB 작업 기록은 남는다. */
    public synchronized void invalidate(Entry evaluated) {
        if (entries.get(evaluated.key) == evaluated) {
            entries.remove(evaluated.key);
            size -= evaluated.forecasts.size();
        }
    }

    public synchronized int size() {
        return size;
    }

    public synchronized List<Entry> snapshot() {
        return List.copyOf(entries.values());
    }

    /**
     * 용량 조절을 위해 가장 오래 보관한 항목을 제거한다. 저장된 평가 자체를 삭제하는 연산은 아니다.
     */
    public synchronized void evictOldest() {
        Map.Entry<Key, Entry> oldest = entries.pollFirstEntry();
        if (oldest == null) {
            throw new IllegalStateException("교체할 메모리 대기가 없다");
        }
        size -= oldest.getValue().forecasts.size();
    }

    public synchronized void clear() {
        entries.clear();
        size = 0;
    }

    private static Key keyOf(PendingForecast forecast) {
        return new Key(forecast.routeVersionId(), forecast.vehicleObservationId());
    }

    public record Key(long routeVersionId, long observationId) {
    }

    /** 인스턴스 동일성으로 재복원 전의 오래된 완료 처리를 구분한다. */
    public static final class Entry {
        private final Key key;
        private final long qualityRevision;
        private final long workRevision;
        private final List<PendingForecast> forecasts;

        private Entry(
            Key key,
            final long qualityRevision,
            final long workRevision,
            List<PendingForecast> forecasts
        ) {
            this.key = key;
            this.qualityRevision = qualityRevision;
            this.workRevision = workRevision;
            this.forecasts = forecasts;
        }

        public Key key() {
            return key;
        }

        public long qualityRevision() {
            return qualityRevision;
        }

        public long workRevision() {
            return workRevision;
        }

        public List<PendingForecast> forecasts() {
            return forecasts;
        }
    }

    private static int forecastCountOf(Entry entry) {
        if (entry == null) {
            return 0;
        }
        return entry.forecasts.size();
    }
}
