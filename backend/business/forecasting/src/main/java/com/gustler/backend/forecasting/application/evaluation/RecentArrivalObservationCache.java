package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RecentArrivalObservationCache {
    private final boolean enabled;
    private final int capacity;
    private final Duration retention;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private ObservationDeliveryService deliveries;

    @Autowired(required = false)
    void useCommittedObservations(ObservationDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    public RecentArrivalObservationCache(
        @Value("${forecast.evaluation.observation-cache.enabled:false}") boolean enabled,
        @Value("${forecast.evaluation.observation-cache.max-observations:100000}") int capacity,
        @Value("${forecast.evaluation.observation-cache.retention:50m}") Duration retention
    ) {
        if (capacity < 1 || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("관측 보관 개수와 시간은 양수여야 한다");
        }
        this.enabled = enabled;
        this.capacity = capacity;
        this.retention = retention;
    }

    public synchronized Round begin(Instant now) {
        Instant cutoff = now.minus(retention);
        entries.values().removeIf(entry -> !entry.candidate().observedAt().isAfter(cutoff));
        return new Round(now);
    }

    private synchronized ArrivalCandidate get(Key key, long revision, Instant now) {
        Entry entry = entries.get(key);
        if (entry == null || entry.revision() != revision
            || !recent(entry.candidate(), now)) return null;
        return entry.candidate();
    }

    private boolean recent(ArrivalCandidate candidate, Instant now) {
        return candidate.observedAt().isAfter(now.minus(retention))
            && !candidate.observedAt().isAfter(now);
    }

    private synchronized void publish(Map<Key, Entry> additions, Instant now) {
        entries.values().removeIf(entry -> !recent(entry.candidate(), now));
        for (var addition : additions.entrySet()) {
            Entry previous = entries.get(addition.getKey());
            if (previous != null && previous.revision() > addition.getValue().revision()) continue;
            if (!recent(addition.getValue().candidate(), now)) continue;
            entries.put(addition.getKey(), addition.getValue());
            trim(entries);
        }
    }

    private void trim(Map<Key, Entry> target) {
        while (target.size() > capacity) {
            var iterator = target.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private record Key(long routeVersionId, long observationId) {
    }
    private record Entry(long revision, ArrivalCandidate candidate) {
    }

    /** 정산 한 회차에서만 사용한다. 커밋 전 자료를 다른 회차에 공개하지 않는다. */
    public final class Round {
        private final Instant now;
        private final Map<Key, Entry> staged = new LinkedHashMap<>();
        private final ObservationDeliveryService.Round delivered;

        private Round(Instant now) {
            this.now = now;
            this.delivered = enabled && deliveries != null ? deliveries.begin(now) : null;
        }

        public List<ArrivalCandidate> read(
            ArrivalObservationQuery query, long version,
            long revision, boolean investigating,
            String vehicle,
            Instant after, int limit
        ) {
            if (!enabled || investigating) return query.findAfter(version, vehicle, after, limit);
            if (delivered != null) return delivered.read(query, version, revision, investigating, vehicle, after, limit);

            List<Long> ids = query.findIdsAfter(version, vehicle, after, limit);
            Map<Long, ArrivalCandidate> found = new LinkedHashMap<>();
            List<Long> missing = new ArrayList<>();
            for (long id : ids) {
                Key key = new Key(version, id);
                Entry pending = staged.get(key);
                ArrivalCandidate candidate = pending != null && pending.revision() == revision
                    ? pending.candidate() : get(key, revision, now);
                if (candidate == null) missing.add(id);
                else found.put(id, candidate);
            }

            for (int from = 0; from < missing.size(); from += 100) {
                List<Long> page = missing.subList(from, Math.min(from + 100, missing.size()));
                for (ArrivalCandidate candidate : query.findByIds(version, vehicle, page)) {
                    found.put(candidate.observationId(), candidate);
                }
            }

            // ID 조회 후 자료가 사라졌다면 기존 조회 결과로 다시 판단한다.
            if (ids.stream().anyMatch(id -> !found.containsKey(id))) {
                return query.findAfter(version, vehicle, after, limit);
            }

            List<ArrivalCandidate> ordered = ids.stream()
                .map(found::get)
                .toList();
            for (ArrivalCandidate candidate : ordered) {
                if (recent(candidate, now)) {
                    staged.put(new Key(version, candidate.observationId()), new Entry(revision, candidate));
                    trim(staged);
                }
            }
            return ordered;
        }

        public void committed(Instant committedAt) {
            publish(staged, committedAt);
            staged.clear();
        }
    }
}
