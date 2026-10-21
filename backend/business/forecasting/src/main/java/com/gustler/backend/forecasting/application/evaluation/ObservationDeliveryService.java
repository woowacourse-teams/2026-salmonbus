package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.evaluation.RecentObservationBatches.Header;
import com.gustler.backend.forecasting.application.evaluation.RecentObservationBatches.Row;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Kind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 커밋된 관측을 용량과 보존 시간 안에서 보관한다.
 * 전달 누락이나 재시작은 제한된 DB 복원으로 보완한다.
 */
@Component
@ConditionalOnProperty(prefix = "forecast.evaluation.observation-cache", name = "enabled", havingValue = "true")
public class ObservationDeliveryService implements ObservationDeliveryReceiver {
    private final RecentObservationBatches source;
    private final Clock clock;
    private final int capacity;
    private final Duration retention;
    private final Map<Key, Entry> batches = new LinkedHashMap<>();
    private int retained;

    public ObservationDeliveryService(
        RecentObservationBatches source,
        Clock clock,
        @Value("${forecast.evaluation.observation-cache.max-observations:100000}") final int capacity,
        @Value("${forecast.evaluation.observation-cache.retention:50m}") Duration retention
    ) {
        if (capacity < 1 || isInvalidRetention(retention)) {
            throw new IllegalArgumentException("관측 보관 개수와 시간은 양수여야 한다");
        }
        this.source = source;
        this.clock = clock;
        this.capacity = capacity;
        this.retention = retention;
    }

    private boolean isInvalidRetention(Duration retention) {
        return retention.isNegative() || retention.isZero();
    }

    /** afterCommit에서 DB 연결을 사용하지 않는다. 초과분은 다음 조회에서 DB로 복원한다. */
    @Override
    public synchronized boolean receive(final long qualityRevision, ObservationDeliveryBatch batch) {
        prune(clock.instant().minus(retention));
        Key key = new Key(batch.routeVersionId(), batch.batchId());
        Entry previous = batches.get(key);
        if (previous != null && previous.attempt() > batch.attempt()) {
            return false;
        }
        if (isSupersededStoredBatch(previous, batch, qualityRevision)) {
            return false;
        }
        if (batch.kind() == Kind.CLEARED) {
            remove(key);
            return true;
        }
        if (!batch.observedAt().isAfter(clock.instant().minus(retention))) {
            return false;
        }
        return put(key, new Entry(batch.attempt(), batch.observedAt(), qualityRevision, batch, List.of()));
    }

    private boolean isSupersededStoredBatch(Entry previous, ObservationDeliveryBatch batch, long qualityRevision) {
        if (previous == null || batch.kind() != Kind.STORED) {
            return false;
        }
        if (previous.attempt() != batch.attempt()) {
            return false;
        }
        return previous.revision() > qualityRevision;
    }

    public synchronized Round begin(Instant now) {
        prune(now.minus(retention));
        return new Round(now.minus(retention));
    }

    private void prune(Instant cutoff) {
        Iterator<Entry> iterator = batches.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!entry.observedAt().isAfter(cutoff)) {
                retained -= entry.weight();
                iterator.remove();
            }
        }
    }

    private void remove(Key key) {
        Entry old = batches.remove(key);
        if (old != null) {
            retained -= old.weight();
        }
    }

    private boolean put(Key key, Entry entry) {
        if (entry.weight() > capacity) {
            return false;
        }
        remove(key);
        while (retained + entry.weight() > capacity) {
            remove(batches.keySet().iterator().next());
        }
        batches.put(key, entry);
        retained += entry.weight();
        return true;
    }

    private synchronized List<ArrivalCandidate> prepare(final long version, final long revision, Round round) {
        List<Header> headers = source.headers(version, round.cutoff, capacity + 1);
        if (headersExceedRoundLimits(headers, round)) {
            return null;
        }

        Map<Long, List<ArrivalCandidate>> ready = new HashMap<>();
        List<Header> missing = addReusableBatches(version, revision, headers, ready);
        if (!restoreMissingBatchesWithinRoundLimits(version, missing, round, ready)) {
            return null;
        }
        cacheReadyBatches(version, revision, headers, ready);
        if (ready.size() != headers.size()) {
            return null;
        }
        return sortReadyCandidatesAndConsumeBudget(ready, round);
    }

    private boolean headersExceedRoundLimits(List<Header> headers, Round round) {
        return headers.size() > capacity || headers.stream().anyMatch(header -> header.rows() < 0)
            || headers.stream().mapToLong(Header::rows).sum() > round.remaining;
    }

    private List<Header> addReusableBatches(
        final long version,
        final long revision,
        List<Header> headers,
        Map<Long, List<ArrivalCandidate>> ready
    ) {
        List<Header> missing = new ArrayList<>();
        for (Header header : headers) {
            Entry entry = batches.get(new Key(version, header.batchId()));
            if (!canReuseBatch(entry, header, revision)) {
                missing.add(header);
                continue;
            }
            ready.put(header.batchId(), candidatesFromCachedEntry(entry, header));
        }
        return missing;
    }

    private static boolean canReuseBatch(Entry entry, Header header, final long revision) {
        return entry != null
            && entry.attempt() == header.attempt()
            && (entry.raw() != null || entry.observedAt().equals(header.observedAt()))
            && entry.size() == header.rows()
            && entry.revision() == revision;
    }

    private static List<ArrivalCandidate> candidatesFromCachedEntry(Entry entry, Header header) {
        if (entry.raw() == null) {
            return entry.candidates();
        }
        return CollectedArrivalCandidates.from(entry.raw(), header.turnSequence(), header.observedAt());
    }

    private boolean restoreMissingBatchesWithinRoundLimits(
        final long version,
        List<Header> missing,
        Round round,
        Map<Long, List<ArrivalCandidate>> ready
    ) {
        List<Header> recovering = missing.stream()
            .limit(round.recoveryBatches)
            .toList();
        round.recoveryBatches -= recovering.size();
        if (recovering.isEmpty()) {
            return true;
        }
        List<Long> batchIds = recovering.stream().map(Header::batchId).toList();
        List<Row> rows = source.restore(version, batchIds, round.remaining + 1);
        if (rows.size() > round.remaining) {
            return false;
        }
        Map<Long, List<ArrivalCandidate>> candidatesByBatchId = rows.stream()
            .collect(Collectors.groupingBy(Row::batchId, HashMap::new,
                Collectors.mapping(Row::candidate, Collectors.toCollection(ArrayList::new))));
        for (Header header : recovering) {
            List<ArrivalCandidate> candidates =
                List.copyOf(candidatesByBatchId.getOrDefault(header.batchId(), List.of()));
            if (hasCompleteRecoveredBatch(header, candidates)) {
                ready.put(header.batchId(), candidates);
            }
        }
        return true;
    }

    private static boolean hasCompleteRecoveredBatch(Header header, List<ArrivalCandidate> candidates) {
        return candidates.size() == header.rows()
            && candidates.stream().allMatch(candidate -> candidate.observedAt().equals(header.observedAt()));
    }

    private void cacheReadyBatches(
        final long version,
        final long revision,
        List<Header> headers,
        Map<Long, List<ArrivalCandidate>> ready
    ) {
        for (Header header : headers) {
            List<ArrivalCandidate> candidates = ready.get(header.batchId());
            if (candidates != null) {
                put(new Key(version, header.batchId()), new Entry(header.attempt(),
                    header.observedAt(), revision, null, candidates));
            }
        }
    }

    private static List<ArrivalCandidate> sortReadyCandidatesAndConsumeBudget(
        Map<Long, List<ArrivalCandidate>> ready,
        Round round
    ) {
        List<ArrivalCandidate> result = ready.values()
            .stream()
            .flatMap(List::stream)
            .sorted(Comparator.comparing(ArrivalCandidate::observedAt)
                .thenComparingLong(ArrivalCandidate::observationId))
            .toList();
        round.remaining -= result.size();
        return result;
    }

    private record Key(long version, long batch) {
    }
    private record Entry(int attempt, Instant observedAt, long revision,
        ObservationDeliveryBatch raw, List<ArrivalCandidate> candidates) {
        /** 호출자가 가진 원본과 분리해 null·원소·순서를 보존한다. */
        private Entry {
            if (candidates != null) {
                candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            }
        }

        int size() {
            if (raw == null) {
                return candidates.size();
            }
            return raw.rows().size();
        }

        int weight() {
            return Math.max(1, size());
        }
    }

    /** 기존 정산의 노선 품질 잠금을 획득한 뒤 사용한다. 회차의 목록을 콜백이 바꾸지 않는다. */
    public final class Round {
        private final Instant cutoff;
        private final Map<Long, List<ArrivalCandidate>> routes = new HashMap<>();
        private int remaining = capacity;
        private int recoveryBatches = 100;

        private Round(Instant cutoff) {
            this.cutoff = cutoff;
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
            if (investigating || after.isBefore(cutoff)) {
                return query.findAfter(version, vehicle, after, limit);
            }
            if (!routes.containsKey(version)) {
                routes.put(version, prepare(version, revision, this));
            }
            List<ArrivalCandidate> candidates = routes.get(version);
            if (candidates == null) {
                return query.findAfter(version, vehicle, after, limit);
            }
            return candidates.stream()
                .filter(row -> Objects.equals(row.vehicle().vehicleId(), vehicle)
                    && row.observedAt().isAfter(after))
                .limit(limit)
                .toList();
        }
    }
}
