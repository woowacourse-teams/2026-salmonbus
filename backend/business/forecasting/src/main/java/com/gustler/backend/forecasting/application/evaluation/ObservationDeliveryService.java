package com.gustler.backend.forecasting.application.evaluation;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
        @Value("${forecast.evaluation.observation-cache.max-observations:100000}") int capacity,
        @Value("${forecast.evaluation.observation-cache.retention:50m}") Duration retention
    ) {
        if (capacity < 1 || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("관측 보관 개수와 시간은 양수여야 한다");
        }
        this.source = source;
        this.clock = clock;
        this.capacity = capacity;
        this.retention = retention;
    }

    /** afterCommit에서 DB 연결을 사용하지 않는다. 초과분은 다음 조회에서 DB로 복원한다. */
    @Override
    public synchronized boolean receive(long qualityRevision, ObservationDeliveryBatch batch) {
        prune(clock.instant().minus(retention));
        Key key = new Key(batch.routeVersionId(), batch.batchId());
        Entry previous = batches.get(key);
        if (previous != null && previous.attempt() > batch.attempt()) return false;
        if (previous != null && previous.attempt() == batch.attempt()
            && previous.revision() > qualityRevision
            && batch.kind() == ObservationDeliveryBatch.Kind.STORED) return false;
        if (batch.kind() == ObservationDeliveryBatch.Kind.CLEARED) {
            remove(key);
            return true;
        }
        if (!batch.observedAt().isAfter(clock.instant().minus(retention))) return false;
        return put(key, new Entry(batch.attempt(), batch.observedAt(), qualityRevision, batch, List.of()));
    }

    public synchronized Round begin(Instant now) {
        prune(now.minus(retention));
        return new Round(now.minus(retention));
    }

    private void prune(Instant cutoff) {
        var iterator = batches.values().iterator();
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
        if (old != null) retained -= old.weight();
    }

    private boolean put(Key key, Entry entry) {
        if (entry.weight() > capacity) return false;
        remove(key);
        while (retained + entry.weight() > capacity) remove(batches.keySet().iterator().next());
        batches.put(key, entry);
        retained += entry.weight();
        return true;
    }

    private synchronized List<ArrivalCandidate> prepare(long version, long revision, Round round) {
        List<RecentObservationBatches.Header> headers = source.headers(version, round.cutoff, capacity + 1);
        if (headers.size() > capacity || headers.stream().anyMatch(header -> header.rows() < 0)
            || headers.stream()
            .mapToLong(RecentObservationBatches.Header::rows)
            .sum() > round.remaining) return null;

        Map<Long, List<ArrivalCandidate>> ready = new HashMap<>();
        List<RecentObservationBatches.Header> missing = new ArrayList<>();
        for (var header : headers) {
            Entry entry = batches.get(new Key(version, header.batchId()));
            if (entry == null || entry.attempt() != header.attempt()
                || (entry.raw() == null && !entry.observedAt().equals(header.observedAt()))
                || entry.size() != header.rows()) {
                missing.add(header);
            } else if (entry.raw() == null && entry.revision() == revision) {
                ready.put(header.batchId(), entry.candidates());
            } else if (entry.raw() != null && entry.revision() == revision) {
                // Java 나노초와 PostgreSQL 마이크로초의 차이는 저장된 배치 시각으로 통일한다.
                ready.put(header.batchId(), CollectedArrivalCandidates.from(entry.raw(), header.turnSequence(), header.observedAt()));
            } else {
                missing.add(header);
            }
        }

        // 재시작이나 전달 누락 시에도 회차당 복원 배치 수와 본문 수를 제한한다.
        List<RecentObservationBatches.Header> recovering = missing.stream()
            .limit(round.recoveryBatches)
            .toList();
        round.recoveryBatches -= recovering.size();
        if (!recovering.isEmpty()) {
            List<Long> ids = recovering.stream()
                .map(RecentObservationBatches.Header::batchId)
                .toList();
            List<RecentObservationBatches.Row> rows = source.restore(version, ids, round.remaining + 1);
            if (rows.size() > round.remaining) return null;
            Map<Long, List<ArrivalCandidate>> grouped = new HashMap<>();
            for (var row : rows) grouped.computeIfAbsent(row.batchId(), ignored -> new ArrayList<>()).add(row.candidate());
            for (var header : recovering) {
                List<ArrivalCandidate> candidates = List.copyOf(grouped.getOrDefault(header.batchId(), List.of()));
                // 목록 확인 뒤 배치가 재수집/삭제됐거나 LIMIT에서 잘렸다면 완전한 자료가 아니다.
                if (candidates.size() == header.rows()
                    && candidates.stream().allMatch(row -> row.observedAt().equals(header.observedAt()))) {
                    ready.put(header.batchId(), candidates);
                }
            }
        }

        for (var header : headers) {
            List<ArrivalCandidate> candidates = ready.get(header.batchId());
            if (candidates == null) continue;
            put(new Key(version, header.batchId()), new Entry(header.attempt(),
                header.observedAt(), revision, null, candidates));
        }
        if (ready.size() != headers.size()) return null;
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
        int size() { return raw == null ? candidates.size() : raw.rows().size();
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

        public List<ArrivalCandidate> read(
            ArrivalObservationQuery query, long version, long revision,
            boolean investigating,
            String vehicle,
            Instant after, int limit
        ) {
            if (investigating || after.isBefore(cutoff)) return query.findAfter(version, vehicle, after, limit);
            if (!routes.containsKey(version)) routes.put(version, prepare(version, revision, this));
            List<ArrivalCandidate> candidates = routes.get(version);
            if (candidates == null) return query.findAfter(version, vehicle, after, limit);
            return candidates.stream()
                .filter(row -> Objects.equals(row.vehicle().vehicleId(), vehicle)
                    && row.observedAt().isAfter(after))
                .limit(limit)
                .toList();
        }
    }
}
