package com.gustler.backend.forecasting.domain.model.alighting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AlightingSettlementProjection {
    public record Layout(int turn, List<Integer> stops) {
        public Layout {
            stops = stops.stream().distinct().sorted().toList();
        }
    }

    public record Chain(AlightingAwareObservation previous, long id) {
    }

    public record Evidence(AlightingAwareObservation observation, long chain) {
    }

    public record Visit(Evidence arrival, Evidence first, Evidence last) {
    }

    public record Vehicle(AlightingAwareObservation previous, long trip, Map<Integer, Visit> visits) {
        public Vehicle {
            visits = Map.copyOf(visits);
        }
    }

    public record Snapshot(
        int format, long observations, Instant incompleteAt, Instant overflowAt,
        Map<String, Chain> chains, Map<String, Vehicle> vehicles, List<AlightingAwareObservation> pending
    ) {
        public Snapshot {
            if (format != 1) {
                throw new IllegalArgumentException("Unsupported settlement projection format");
            }
            chains = Map.copyOf(chains);
            vehicles = Map.copyOf(vehicles);
            pending = List.copyOf(pending);
        }

        public static Snapshot empty() {
            return new Snapshot(1, 0, null, null, Map.of(), Map.of(), List.of());
        }
    }

    public record IndexedObservation(long id, long chain, int stop, Instant availableAt, boolean knownSeats) {
    }

    public record Pass(long chain, AlightingAwarePassEvent event) {
    }

    private final Layout layout;
    private final int limit;
    private long observations;
    private Instant incompleteAt;
    private Instant overflowAt;
    private final Map<String, Chain> chains;
    private final Map<String, Vehicle> vehicles;
    private final List<AlightingAwareObservation> pending;
    private final List<IndexedObservation> indexed = new ArrayList<>();
    private final List<Pass> passes = new ArrayList<>();

    public AlightingSettlementProjection(Layout layout, int limit, Snapshot snapshot) {
        this.layout = layout;
        this.limit = limit;
        this.observations = snapshot.observations();
        this.incompleteAt = snapshot.incompleteAt();
        this.overflowAt = snapshot.overflowAt();
        this.chains = new HashMap<>(snapshot.chains());
        this.vehicles = new HashMap<>(snapshot.vehicles());
        this.pending = new ArrayList<>(snapshot.pending());
    }

    public void append(Instant at, List<AlightingAwareObservation> rows, boolean incomplete) {
        if (overflowAt != null) {
            return;
        }
        if (incomplete && (incompleteAt == null || at.isBefore(incompleteAt))) {
            incompleteAt = at;
        }
        if (!pending.isEmpty() && !pending.getFirst().receivedAt().equals(at)) {
            flush();
        }
        for (AlightingAwareObservation row : rows) {
            observations++;
            if (observations > limit) {
                overflowAt = at;
                pending.clear();
                return;
            }
            pending.add(directed(row));
        }
    }

    public void completeThrough(Instant through) {
        if (!pending.isEmpty() && !pending.getFirst().receivedAt().isAfter(through)) {
            flush();
        }
        Instant expired = through.minusSeconds(1800);
        chains.values().removeIf(chain -> chain.previous().receivedAt().isBefore(expired));
        vehicles.values().removeIf(vehicle -> vehicle.previous().receivedAt().isBefore(expired));
    }

    private AlightingAwareObservation directed(AlightingAwareObservation row) {
        boolean down = row.stopOrder() > layout.turn()
            || (row.stopOrder() == layout.turn() && row.state() == 2)
            || (row.stopOrder() == 1 && row.state() != 2);
        return new AlightingAwareObservation(row.observationId(), row.batchId(), 0, row.receivedAt(),
            row.vehicleId(), row.passedStopOrder(), row.stopOrder(), row.state(), row.seats(), row.crowd(),
            row.routeVersionId(), row.sourceReceivedAt(), down);
    }

    private void flush() {
        Map<Long, Evidence> evidence = new HashMap<>();
        pending.sort(Comparator.comparing(AlightingAwareObservation::vehicleId)
            .thenComparingLong(AlightingAwareObservation::batchId)
            .thenComparingLong(AlightingAwareObservation::observationId));
        for (AlightingAwareObservation row : pending) {
            Chain previous = chains.get(row.vehicleId());
            long chain = previous == null || row.startsConfirmedChainAfter(previous.previous())
                ? row.observationId() : previous.id();
            chains.put(row.vehicleId(), new Chain(row, chain));
            evidence.put(row.observationId(), new Evidence(row, chain));
            indexed.add(new IndexedObservation(row.observationId(), chain, row.passedStopOrder(),
                row.receivedAt(), row.seats() != null));
        }
        pending.sort(Comparator.comparing(AlightingAwareObservation::vehicleId)
            .thenComparingLong(AlightingAwareObservation::observationId));
        for (AlightingAwareObservation row : pending) {
            advance(evidence.get(row.observationId()));
        }
        pending.clear();
    }

    private void advance(Evidence current) {
        AlightingAwareObservation row = current.observation();
        if (!row.hasVehicleIdentity()) {
            return;
        }
        Vehicle previous = vehicles.get(row.vehicleId());
        AlightingAwareObservation prior = previous == null ? null : previous.previous();
        long trip = row.startsConfirmedTripAfter(prior) ? row.observationId() : previous.trip();
        Map<Integer, Visit> visits = row.startsConfirmedChainAfter(prior)
            ? new HashMap<>() : new HashMap<>(previous.visits());
        for (int i = 1; i + 1 < layout.stops().size(); i++) {
            int before = layout.stops().get(i - 1);
            int target = layout.stops().get(i);
            int next = layout.stops().get(i + 1);
            Visit visit = visits.get(target);
            if (row.passedStopOrder() >= before && row.passedStopOrder() < target) {
                visits.put(target, new Visit(current, null, null));
            } else if (visit != null && row.passedStopOrder() >= target && row.passedStopOrder() < next) {
                visits.put(target, new Visit(visit.arrival(), visit.first() == null ? current : visit.first(), current));
            } else if (visit != null && visit.last() != null && row.passedStopOrder() >= next) {
                if (visit.arrival().observation().seats() != null && visit.last().observation().seats() != null) {
                    passes.add(new Pass(visit.arrival().chain(), new AlightingAwarePassEvent(row.vehicleId(), trip, target,
                        visit.arrival().observation(), visit.last().observation(), row.receivedAt(),
                        visit.first().observation(), row)));
                }
                visits.remove(target);
            }
        }
        vehicles.put(row.vehicleId(), new Vehicle(row, trip, visits));
    }

    public Snapshot snapshot() {
        return new Snapshot(1, observations, incompleteAt, overflowAt, chains, vehicles, pending);
    }

    public List<IndexedObservation> indexed() {
        return List.copyOf(indexed);
    }

    public List<Pass> passes() {
        return List.copyOf(passes);
    }
}
