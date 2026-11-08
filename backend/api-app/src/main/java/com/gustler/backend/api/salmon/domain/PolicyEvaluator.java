package com.gustler.backend.api.salmon.domain;

import com.gustler.backend.api.salmon.domain.ActionModel.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

final class PolicyEvaluator {
    private static final double EPSILON = 1e-12;
    private static final int MAX_UNRESOLVED_SLOTS = 16;
    private static final Comparator<StopEntry> BY_ARRIVAL = Comparator
        .comparing((StopEntry entry) -> entry.arrival().present().orElseThrow())
        .thenComparing(StopEntry::id);

    private final ActionConfig config;
    private final String epoch;
    private final ProbabilitySupply supply;

    PolicyEvaluator(ActionConfig config, String epoch, ProbabilitySupply supply) {
        this.config = config;
        this.epoch = epoch;
        this.supply = supply;
    }

    Outcome stay(StopList list) {
        return singleList(list, new Offset(0), false, false);
    }

    Outcome walk(WalkCandidate candidate) {
        Optional<Span> walk = candidate.walk().present();
        if (walk.isEmpty()) {
            return Outcome.of(new Insufficient(List.of("walk time of " + candidate.id())));
        }
        Offset ready = new Offset(0).plus(config.readyAfterWalk(walk.get()));
        return singleList(candidate.list(), ready, true, candidate.walk().isDerived());
    }

    Outcome reboard(ReboardCandidate candidate) {
        return new ReboardRun(candidate).run();
    }

    private Outcome singleList(StopList list, Offset ready, boolean withMargin, boolean upstreamDerived) {
        if (list instanceof Unavailable unavailable) {
            return Outcome.of(new Insufficient(List.of("list unavailable: " + unavailable.reason())));
        }
        Window window = Window.of(list.entries(), ready, config.horizon());
        if (!window.unresolved().isEmpty()) {
            return Outcome.of(new Insufficient(window.unresolved().stream()
                .map(id -> "arrival of " + id).toList()));
        }
        TimeBasis basis = upstreamDerived || window.derivedIn() ? TimeBasis.DERIVED : TimeBasis.DIRECT;
        if (window.attempts().isEmpty()) {
            Evaluation evaluation = coversWindow(list)
                ? new ConfirmedEmpty(basis)
                : new Insufficient(List.of("list does not cover the window"));
            return new Outcome(evaluation, List.of(), List.of(), Optional.empty(), Optional.empty(), List.of());
        }
        List<Slot> slots = slotsOf(window.attempts(), window.missed(), Optional.empty());
        Evaluation evaluation = solve(resolver -> riskOf(slots, resolver), basis);
        List<Attempt> trail = window.attempts().stream().map(PolicyEvaluator::attemptOf).toList();
        if (!withMargin) {
            return new Outcome(evaluation, trail, List.of(), Optional.empty(), Optional.empty(),
                nearMisses(window, ready));
        }
        List<MarginItem> items = window.attempts().stream()
            .map(entry -> new MarginItem(ready.until(entry.arrival().present().orElseThrow()),
                entry.arrival().knownBound())).toList();
        return new Outcome(evaluation, trail, List.of(), minimumMargin(items), Optional.of(connectionOf(items)),
            nearMisses(window, ready));
    }

    private List<String> nearMisses(Window window, Offset ready) {
        return window.missed().stream()
            .filter(entry -> entry.arrival().knownBound()
                .map(bound -> entry.arrival().present().orElseThrow().until(ready).millis() <= bound.millis())
                .orElse(false))
            .map(StopEntry::id).toList();
    }

    private final class ReboardRun {
        private final ReboardCandidate candidate;

        ReboardRun(ReboardCandidate candidate) {
            this.candidate = candidate;
        }

        Outcome run() {
            if (candidate.opposite() instanceof Unavailable unavailable) {
                return missing("opposite list unavailable: " + unavailable.reason());
            }
            Optional<Span> walkToOpposite = candidate.walkToOpposite().present();
            Window all = Window.of(candidate.opposite().entries(), new Offset(0), config.horizon());
            if (!all.unresolved().isEmpty()) {
                return missing("opposite arrival of " + all.unresolved().get(0));
            }
            Offset t1 = new Offset(0).plus(walkToOpposite.map(config::readyAfterWalk).orElse(new Span(0)));
            List<StopEntry> opposite = all.attempts().stream()
                .filter(entry -> walkToOpposite.isEmpty() || entry.arrival().present().orElseThrow().compareTo(t1) >= 0)
                .toList();
            List<StopEntry> oppositeMissed = all.attempts().stream()
                .filter(entry -> !opposite.contains(entry)).toList();

            List<StopEntry> live = new ArrayList<>();
            for (StopEntry entry : opposite) {
                if (lowerT2(entry).compareTo(config.horizon()) <= 0) {
                    live.add(entry);
                }
            }
            boolean covers = coversWindow(candidate.opposite());
            if (live.isEmpty()) {
                return covers ? new Outcome(new ConfirmedEmpty(timeBasis(opposite)), List.of(), List.of(),
                    Optional.empty(), Optional.empty(), List.of()) : missing("opposite list does not cover the window");
            }
            Optional<Span> ride = candidate.rideTime().present();
            Optional<Span> walkAfter = candidate.walkAfterAlight().present();
            if (walkToOpposite.isEmpty() || ride.isEmpty() || walkAfter.isEmpty()) {
                return missing("walk or ride time of " + candidate.id());
            }
            if (candidate.reboard() instanceof Unavailable unavailable) {
                return missing("reboard list unavailable: " + unavailable.reason());
            }
            Window reboardWindow = Window.of(candidate.reboard().entries(), new Offset(0), config.horizon());
            if (!reboardWindow.unresolved().isEmpty()) {
                return missing("reboard arrival of " + reboardWindow.unresolved().get(0));
            }
            Span afterAlight = config.scaledByAlpha(walkAfter.get());
            List<Included> included = new ArrayList<>();
            List<Slot> boundarySlots = new ArrayList<>();
            for (StopEntry entry : opposite) {
                Offset arrival = entry.arrival().present().orElseThrow();
                Offset t2 = config.ceilToResolution(arrival.plus(ride.get()).plus(afterAlight));
                if (t2.compareTo(config.horizon()) <= 0) {
                    included.add(new Included(entry, arrival, t2, reboardWindow.attemptsFrom(t2, config.horizon()),
                        reboardWindow.missedBefore(t2)));
                } else if (lowerT2(entry).compareTo(config.horizon()) <= 0) {
                    boundarySlots.addAll(boundarySlotsOf(entry, lowerT2(entry), reboardWindow));
                }
            }
            if (boundarySlots.stream().anyMatch(slot -> supply.valueFor(slot) instanceof Absent)) {
                return missing("probabilities for boundary branches of " + candidate.id());
            }
            if (included.isEmpty()) {
                return covers ? new Outcome(new ConfirmedEmpty(timeBasis(opposite)), List.of(), List.of(),
                    Optional.empty(), Optional.empty(), List.of()) : missing("opposite list does not cover the window");
            }
            List<Slot> chain = oppositeSlots(opposite, oppositeMissed, included.size());
            boolean reboardCovers = coversWindow(candidate.reboard());
            TimeBasis basis = reboardBasis(opposite, reboardWindow);
            Evaluation evaluation = solve(resolver -> reboardRisk(chain, included, reboardCovers, resolver), basis);

            Resolver optimistic = new Resolver(java.util.Map.of());
            List<Double> chances = branchChances(chain, optimistic);
            List<MarginItem> items = new ArrayList<>();
            for (int index = 0; index < included.size(); index++) {
                if (chances.get(index) <= 0) {
                    continue;
                }
                Included branch = included.get(index);
                items.add(new MarginItem(t1.until(branch.arrival()), branch.entry().arrival().knownBound()));
                for (StopEntry entry : branch.attempts()) {
                    Optional<Span> bound = sumBounds(entry.arrival().knownBound(),
                        branch.entry().arrival().knownBound(), candidate.rideTime().knownBound());
                    items.add(new MarginItem(branch.t2().until(entry.arrival().present().orElseThrow()), bound));
                }
            }
            List<Attempt> first = included.stream().map(branch -> attemptOf(branch.entry())).toList();
            List<Attempt> second = included.get(0).attempts().stream().map(PolicyEvaluator::attemptOf).toList();
            return new Outcome(evaluation, first, second, minimumMargin(items), Optional.of(connectionOf(items)),
                List.of());
        }

        private Offset lowerT2(StopEntry entry) {
            Offset arrival = lowerOffset(entry.arrival());
            Span ride = lowerSpan(candidate.rideTime());
            Span afterAlight = config.scaledByAlpha(lowerSpan(candidate.walkAfterAlight()));
            return arrival.plus(ride).plus(afterAlight);
        }

        private List<Slot> boundarySlotsOf(StopEntry entry, Offset t2, Window reboardWindow) {
            List<StopEntry> attempts = reboardWindow.attemptsFrom(t2, config.horizon());
            List<StopEntry> missed = reboardWindow.missedBefore(t2);
            return slotsOf(attempts, missed, Optional.of(entry.id()));
        }

        private List<Slot> oppositeSlots(List<StopEntry> opposite, List<StopEntry> missed, int count) {
            return slotsOf(opposite.subList(0, count), missed, Optional.empty());
        }

        private List<Double> branchChances(List<Slot> chain, Resolver resolver) {
            List<Double> chances = new ArrayList<>();
            double survive = 1;
            for (Slot slot : chain) {
                double beta = resolver.q(slot);
                chances.add(beta * survive);
                survive *= 1 - beta;
            }
            return chances;
        }

        private double reboardRisk(List<Slot> chain, List<Included> included, boolean reboardCovers,
                                   Resolver resolver) {
            List<Double> chances = branchChances(chain, resolver);
            double success = 0;
            boolean undefined = false;
            for (int index = 0; index < included.size(); index++) {
                Included branch = included.get(index);
                double branchRisk;
                if (branch.attempts().isEmpty()) {
                    if (!reboardCovers && chances.get(index) > 0) {
                        undefined = true;
                    }
                    branchRisk = 1;
                } else {
                    branchRisk = riskOf(slotsOf(branch.attempts(), branch.missed(),
                        Optional.of(branch.entry().id())), resolver);
                }
                success += chances.get(index) * (1 - branchRisk);
            }
            return undefined ? Double.NaN : 1 - success;
        }

        private TimeBasis timeBasis(List<StopEntry> opposite) {
            boolean derived = opposite.stream().anyMatch(entry -> entry.arrival().isDerived())
                || candidate.walkToOpposite().isDerived() || candidate.rideTime().isDerived()
                || candidate.walkAfterAlight().isDerived();
            return derived ? TimeBasis.DERIVED : TimeBasis.DIRECT;
        }

        private TimeBasis reboardBasis(List<StopEntry> opposite, Window reboardWindow) {
            return timeBasis(opposite) == TimeBasis.DERIVED || reboardWindow.derivedIn()
                ? TimeBasis.DERIVED : TimeBasis.DIRECT;
        }

        private Outcome missing(String what) {
            return Outcome.of(new Insufficient(List.of(what)));
        }
    }

    private record Included(StopEntry entry, Offset arrival, Offset t2, List<StopEntry> attempts,
                            List<StopEntry> missed) {
    }

    private record MarginItem(Span margin, Optional<Span> bound) {
    }

    private List<Slot> slotsOf(List<StopEntry> attempts, List<StopEntry> missed, Optional<String> oppositeBoarded) {
        List<Slot> slots = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> skipped = missed.stream().map(StopEntry::id).toList();
        for (StopEntry attempt : attempts) {
            SlotKind kind = !failed.isEmpty() ? SlotKind.S_FAIL
                : !skipped.isEmpty() ? SlotKind.S_MISS : SlotKind.S_FIRST;
            ConditionHistory condition = new ConditionHistory(epoch, new java.util.HashSet<>(failed),
                new java.util.HashSet<>(skipped), oppositeBoarded);
            slots.add(new Slot(attempt.id(), kind, condition));
            failed.add(attempt.id());
        }
        return slots;
    }

    private double riskOf(List<Slot> slots, Resolver resolver) {
        double risk = 1;
        for (Slot slot : slots) {
            risk *= 1 - resolver.q(slot);
        }
        return risk;
    }

    private Evaluation solve(ToDoubleFunction<Resolver> risk, TimeBasis basis) {
        Resolver probe = new Resolver(java.util.Map.of());
        risk.applyAsDouble(probe);
        List<Slot> unresolved = List.copyOf(probe.unresolved());
        if (unresolved.size() > MAX_UNRESOLVED_SLOTS) {
            return new Insufficient(List.of("too many unresolved probabilities"));
        }
        Double agreed = null;
        for (int mask = 0; mask < 1 << unresolved.size(); mask++) {
            java.util.Map<Slot, Double> substitution = new java.util.HashMap<>();
            for (int index = 0; index < unresolved.size(); index++) {
                substitution.put(unresolved.get(index), (mask >> index & 1) == 1 ? 1.0 : 0.0);
            }
            double value = risk.applyAsDouble(new Resolver(substitution));
            if (Double.isNaN(value)) {
                return new Insufficient(unresolved.stream().map(slot -> "probability of " + slot.attempt()).toList());
            }
            if (agreed == null) {
                agreed = value;
            } else if (Math.abs(agreed - value) > EPSILON) {
                return new Insufficient(unresolved.stream().map(slot -> "probability of " + slot.attempt()).toList());
            }
        }
        Assumption assumption = probe.independent() ? Assumption.INDEP : Assumption.EXACT;
        return new Evaluable(agreed, assumption, basis);
    }

    private final class Resolver {
        private final java.util.Map<Slot, Double> substitution;
        private final LinkedHashSet<Slot> unresolved = new LinkedHashSet<>();
        private boolean independent;

        Resolver(java.util.Map<Slot, Double> substitution) {
            this.substitution = substitution;
        }

        double q(Slot slot) {
            return switch (supply.valueFor(slot)) {
                case Matched matched -> matched.value();
                case Marginal marginal -> {
                    independent = true;
                    yield marginal.value();
                }
                case Absent ignored -> {
                    unresolved.add(slot);
                    yield substitution.getOrDefault(slot, 0.5);
                }
            };
        }

        LinkedHashSet<Slot> unresolved() {
            return unresolved;
        }

        boolean independent() {
            return independent;
        }
    }

    private record Window(List<StopEntry> resolved, List<String> unresolved, Offset ready, Offset horizon) {
        static Window of(List<StopEntry> entries, Offset ready, Offset horizon) {
            List<StopEntry> resolved = new ArrayList<>();
            List<String> unresolved = new ArrayList<>();
            for (StopEntry entry : entries) {
                Optional<Offset> point = entry.arrival().present();
                boolean boundary = point.isPresent() && entry.arrival().isDerived()
                    && point.get().compareTo(horizon) > 0 && lowerOffset(entry.arrival()).compareTo(horizon) <= 0;
                if (point.isEmpty() || boundary) {
                    unresolved.add(entry.id());
                } else {
                    resolved.add(entry);
                }
            }
            resolved.sort(BY_ARRIVAL);
            return new Window(resolved, unresolved, ready, horizon);
        }

        List<StopEntry> attempts() {
            return attemptsFrom(ready, horizon);
        }

        List<StopEntry> missed() {
            return missedBefore(ready);
        }

        List<StopEntry> attemptsFrom(Offset from, Offset limit) {
            return resolved.stream().filter(entry -> {
                Offset arrival = entry.arrival().present().orElseThrow();
                return arrival.compareTo(from) >= 0 && arrival.compareTo(limit) <= 0;
            }).toList();
        }

        List<StopEntry> missedBefore(Offset from) {
            return resolved.stream()
                .filter(entry -> entry.arrival().present().orElseThrow().compareTo(from) < 0).toList();
        }

        boolean derivedIn() {
            return attempts().stream().anyMatch(entry -> entry.arrival().isDerived());
        }
    }

    private static boolean coversWindow(StopList list) {
        return list instanceof Queried queried && queried.coverage() == Coverage.COVERS_WINDOW;
    }

    private static Attempt attemptOf(StopEntry entry) {
        return new Attempt(entry.id(), entry.arrival().present().orElseThrow());
    }

    private static Offset lowerOffset(Field<Offset> field) {
        return switch (field) {
            case Observed<Offset> observed -> observed.value();
            case Derived<Offset> derived -> derived.bound() instanceof Finite finite
                ? new Offset(Math.max(0, derived.value().millis() - finite.bound().millis())) : new Offset(0);
            case Missing<Offset> ignored -> new Offset(0);
        };
    }

    private static Span lowerSpan(Field<Span> field) {
        return switch (field) {
            case Observed<Span> observed -> observed.value();
            case Derived<Span> derived -> derived.bound() instanceof Finite finite
                ? new Span(Math.max(0, derived.value().millis() - finite.bound().millis())) : new Span(0);
            case Missing<Span> ignored -> new Span(0);
        };
    }

    @SafeVarargs
    private static Optional<Span> sumBounds(Optional<Span>... bounds) {
        Span total = new Span(0);
        for (Optional<Span> bound : bounds) {
            if (bound.isEmpty()) {
                return Optional.empty();
            }
            total = total.plus(bound.get());
        }
        return Optional.of(total);
    }

    private static Optional<Span> minimumMargin(List<MarginItem> items) {
        return items.stream().map(MarginItem::margin).min(Comparator.naturalOrder());
    }

    private static ConnectionTag connectionOf(List<MarginItem> items) {
        boolean within = items.stream().anyMatch(item -> item.bound().isPresent()
            && item.margin().millis() <= item.bound().get().millis());
        if (within) {
            return ConnectionTag.MARGIN_WITHIN_BOUND;
        }
        boolean unknown = items.stream().anyMatch(item -> item.bound().isEmpty());
        return unknown ? ConnectionTag.BOUND_UNKNOWN : ConnectionTag.MARGIN_ABOVE_BOUND;
    }
}
