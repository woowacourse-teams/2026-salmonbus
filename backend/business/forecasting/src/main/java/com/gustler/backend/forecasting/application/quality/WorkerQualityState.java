package com.gustler.backend.forecasting.application.quality;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;

public final class WorkerQualityState {
    private final LongFunction<Snapshot> query;
    private final int maxRoutes;
    private final LinkedHashMap<Long, Snapshot> snapshots = new LinkedHashMap<>();
    private long generation;
    private int changing;

    public WorkerQualityState(LongFunction<Snapshot> query, int maxRoutes) {
        if (maxRoutes < 1) throw new IllegalArgumentException("보관할 노선 수는 양수여야 한다");
        this.query = java.util.Objects.requireNonNull(query);
        this.maxRoutes = maxRoutes;
    }

    public Optional<Read> read(long version) {
        long before;
        synchronized (this) {
            if (changing != 0) return Optional.empty();
            Snapshot cached = snapshots.get(version);
            if (cached != null) return Optional.of(new Read(generation, cached));
            before = generation;
        }

        // DB 조회 중에는 이 객체의 모니터를 잡지 않는다.
        Snapshot loaded = java.util.Objects.requireNonNull(query.apply(version));

        synchronized (this) {
            if (changing != 0 || generation != before) return Optional.empty();
            snapshots.put(version, loaded);
            while (snapshots.size() > maxRoutes) snapshots.pollFirstEntry();
            return Optional.of(new Read(generation, loaded));
        }
    }

    public synchronized boolean stillCurrent(Read read) {
        return changing == 0 && generation == read.generation();
    }

    /** DB 조회가 없는 관측 캐시 전달에만 사용한다. 검사와 전달 사이의 품질 변경을 막는다. */
    public synchronized boolean publishIfCurrent(Read read, Runnable publish) {
        if (!stillCurrent(read)) return false;
        publish.run();
        return true;
    }

    public synchronized Change beginChange() {
        generation++;
        changing++;
        snapshots.clear();
        return new Change();
    }

    public record Snapshot(long revision, Set<String> investigatingVehicles) {
        public Snapshot {
            if (revision < 1) throw new IllegalArgumentException("품질 버전은 양수여야 한다");
            investigatingVehicles = Set.copyOf(investigatingVehicles);
        }

        public boolean anyInvestigationPending() {
            return !investigatingVehicles.isEmpty();
        }

        public boolean investigationPending(String vehicle) {
            return investigatingVehicles.contains("")
                || (vehicle != null && investigatingVehicles.contains(vehicle));
        }
    }

    public record Read(long generation, Snapshot snapshot) {
    }

    // 트랜잭션 종료 시 이 변경만 해제한다. 커밋·롤백 모두 다음 사용 때 재조회한다.
    public final class Change implements AutoCloseable {
        private boolean closed;

        private Change() {
        }

        @Override
        public void close() {
            synchronized (WorkerQualityState.this) {
                if (closed) return;
                closed = true;
                changing--;
                generation++;
                snapshots.clear();
            }
        }
    }
}
