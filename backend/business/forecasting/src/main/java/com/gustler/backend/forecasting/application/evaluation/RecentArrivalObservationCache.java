package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 정산 회차의 후속 관측을 품질 판본과 보존 시간으로 제한해 재사용한다.
 * 아직 커밋하지 않은 회차 자료는 공유하지 않는다.
 */
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
        @Value("${forecast.evaluation.observation-cache.enabled:false}") final boolean enabled,
        @Value("${forecast.evaluation.observation-cache.max-observations:100000}") final int capacity,
        @Value("${forecast.evaluation.observation-cache.retention:50m}") Duration retention
    ) {
        if (capacity < 1 || isInvalidRetention(retention)) {
            throw new IllegalArgumentException("관측 보관 개수와 시간은 양수여야 한다");
        }
        this.enabled = enabled;
        this.capacity = capacity;
        this.retention = retention;
    }

    private boolean isInvalidRetention(Duration retention) {
        return retention.isZero() || retention.isNegative();
    }

    public synchronized Round begin(Instant now) {
        Instant cutoff = now.minus(retention);
        entries.values().removeIf(entry -> !entry.candidate().observedAt().isAfter(cutoff));
        return new Round(now);
    }

    private synchronized ArrivalCandidate get(Key key, final long revision, Instant now) {
        Entry entry = entries.get(key);
        if (!isReusable(entry, revision, now)) {
            return null;
        }
        return entry.candidate();
    }

    private boolean isReusable(Entry entry, long revision, Instant now) {
        if (entry == null || entry.revision() != revision) {
            return false;
        }
        return recent(entry.candidate(), now);
    }

    private boolean recent(ArrivalCandidate candidate, Instant now) {
        return candidate.observedAt().isAfter(now.minus(retention))
            && !candidate.observedAt().isAfter(now);
    }

    private synchronized void publish(Map<Key, Entry> additions, Instant now) {
        entries.values().removeIf(entry -> !recent(entry.candidate(), now));
        for (Map.Entry<Key, Entry> addition : additions.entrySet()) {
            Key key = addition.getKey();
            Entry entry = addition.getValue();
            Entry previous = entries.get(key);
            if (previous != null && previous.revision() > entry.revision()) {
                continue;
            }
            if (!recent(entry.candidate(), now)) {
                continue;
            }
            entries.put(key, entry);
            trim(entries);
        }
    }

    private void trim(Map<Key, Entry> target) {
        while (target.size() > capacity) {
            Iterator<Key> iterator = target.keySet().iterator();
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
            this.delivered = beginDelivery(now);
        }

        private ObservationDeliveryService.Round beginDelivery(Instant now) {
            if (enabled && deliveries != null) {
                return deliveries.begin(now);
            }
            return null;
        }

        private ArrivalCandidate candidateFor(Key key, Entry pending, final long revision) {
            if (pending != null && pending.revision() == revision) {
                return pending.candidate();
            }
            return get(key, revision, now);
        }

        /**
         * 품질 조사 중이거나 캐시로 안전하게 답할 수 없으면 DB 조회로 되돌아간다. 차량·시각 범위와 결과 개수 제한을 유지한다.
         */
        public List<ArrivalCandidate> read(
            ArrivalObservationQuery query,
            final long version,
            final long revision,
            final boolean investigating,
            String vehicle,
            Instant after,
            final int limit
        ) {
            if (!enabled || investigating) {
                return query.findAfter(version, vehicle, after, limit);
            }
            if (delivered != null) {
                return delivered.read(query, version, revision, investigating, vehicle, after, limit);
            }

            List<Long> ids = query.findIdsAfter(version, vehicle, after, limit);
            Map<Long, ArrivalCandidate> found = new LinkedHashMap<>();
            List<Long> missing = new ArrayList<>();
            for (final long id : ids) {
                Key key = new Key(version, id);
                Entry pending = staged.get(key);
                ArrivalCandidate candidate = candidateFor(key, pending, revision);
                if (candidate == null) {
                    missing.add(id);
                    continue;
                }
                found.put(id, candidate);
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
