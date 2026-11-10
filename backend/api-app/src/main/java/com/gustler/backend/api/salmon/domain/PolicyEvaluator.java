package com.gustler.backend.api.salmon.domain;

import com.gustler.backend.api.salmon.domain.ActionModel.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

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
        Evaluation evaluation = solve(resolver -> riskOf(slots, resolver), basis, List.of());
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

        /**
         * 맞은편으로 건너가 반대 방향 버스를 타고, 내려서 원래 방향 버스를 다시 타는 경로의 실패 위험을 계산한다.
         *
         * <p>이 메서드는 판정 순서만 보여 주고 계산은 단계 메서드에 맡긴다. 검사 하나하나가 앞 검사의 결과를 전제로 하기
         * 때문에 순서를 바꾸면 결과가 바뀐다. 순서는 다음과 같다.
         * <ol>
         *   <li>맞은편 목록을 검증한다. 목록이 없거나 도착을 모르는 버스가 있으면 근거 부족이다.</li>
         *   <li>t2를 가장 짧게 잡아도 H 안에 드는 맞은편 버스가 없으면 확인된 공집합으로 끝낸다(목록이 창을 덮지 않으면
         *       근거 부족). 하한값은 모르는 시간을 0으로 두고 계산하므로 도보, 탑승 시간이 없어도 이 검사를 할 수 있다.
         *       그래서 시간 검증보다 앞에 둔다.</li>
         *   <li>도보 시간, 탑승 시간, 재탑승 목록을 검증한다.</li>
         *   <li>맞은편 버스마다 t2를 계산해 포함 가지(t2가 H 안)와 경계 가지(t2는 H 밖, 하한은 H 안)로 나눈다.</li>
         *   <li>경계 가지의 확률이 하나라도 없으면 근거 부족으로 끝낸다. 이 검사는 포함 가지가 비었는지 보기 전에 한다.
         *       순서가 바뀌면 경계 가지만 있고 그 확률이 없는 경우를 확인된 공집합으로 잘못 돌려준다. 확률이 모두 있고
         *       포함 가지가 비었으면 2번과 같은 결과로 끝낸다.</li>
         *   <li>위험도를 풀고 여유 시간과 시도 순서를 붙인다.</li>
         * </ol>
         */
        Outcome run() {
            if (candidate.opposite() instanceof Unavailable unavailable) {
                return missing("opposite list unavailable: " + unavailable.reason());
            }
            Window oppositeWindow = Window.of(candidate.opposite().entries(), new Offset(0), config.horizon());
            if (!oppositeWindow.unresolved().isEmpty()) {
                return missing("opposite arrival of " + oppositeWindow.unresolved().get(0));
            }
            Reach reach = reachOf(oppositeWindow);
            if (reach.boardable().stream().noneMatch(this::returnsWithinHorizonAtBest)) {
                return emptyOrUncovered(reach.boardable());
            }
            Optional<Span> ride = candidate.rideTime().present();
            Optional<Span> walkAfter = candidate.walkAfterAlight().present();
            if (candidate.walkToOpposite().present().isEmpty() || ride.isEmpty() || walkAfter.isEmpty()) {
                return missing("walk or ride time of " + candidate.id());
            }
            if (candidate.reboard() instanceof Unavailable unavailable) {
                return missing("reboard list unavailable: " + unavailable.reason());
            }
            Window reboardWindow = Window.of(candidate.reboard().entries(), new Offset(0), config.horizon());
            if (!reboardWindow.unresolved().isEmpty()) {
                return missing("reboard arrival of " + reboardWindow.unresolved().get(0));
            }
            Branches branches = branchesOf(reach.boardable(), ride.get(), walkAfter.get(), reboardWindow);
            if (branches.boundarySlots().stream().anyMatch(slot -> supply.valueFor(slot) instanceof Absent)) {
                return missing("probabilities for boundary branches of " + candidate.id());
            }
            if (branches.included().isEmpty()) {
                return emptyOrUncovered(reach.boardable());
            }
            return outcomeOf(reach, branches.included(), reboardWindow);
        }

        private Reach reachOf(Window oppositeWindow) {
            Optional<Span> walkToOpposite = candidate.walkToOpposite().present();
            Offset t1 = new Offset(0).plus(walkToOpposite.map(config::readyAfterWalk).orElse(new Span(0)));
            List<StopEntry> boardable = oppositeWindow.attempts().stream()
                .filter(entry -> walkToOpposite.isEmpty() || entry.arrival().present().orElseThrow().compareTo(t1) >= 0)
                .toList();
            List<StopEntry> missed = oppositeWindow.attempts().stream()
                .filter(entry -> !boardable.contains(entry)).toList();
            return new Reach(t1, boardable, missed);
        }

        private Branches branchesOf(List<StopEntry> boardable, Span ride, Span walkAfter, Window reboardWindow) {
            Span afterAlight = config.scaledByAlpha(walkAfter);
            List<Included> included = new ArrayList<>();
            List<Slot> boundarySlots = new ArrayList<>();
            for (StopEntry entry : boardable) {
                Offset arrival = entry.arrival().present().orElseThrow();
                Offset t2 = config.ceilToResolution(arrival.plus(ride).plus(afterAlight));
                if (t2.compareTo(config.horizon()) <= 0) {
                    included.add(new Included(entry, arrival, t2, reboardWindow.attemptsFrom(t2, config.horizon()),
                        reboardWindow.missedBefore(t2)));
                } else if (returnsWithinHorizonAtBest(entry)) {
                    boundarySlots.addAll(boundarySlotsOf(entry, lowerT2(entry), reboardWindow));
                }
            }
            return new Branches(included, boundarySlots);
        }

        private Outcome outcomeOf(Reach reach, List<Included> included, Window reboardWindow) {
            List<Slot> chain = oppositeSlots(reach.boardable(), reach.missed(), included.size());
            boolean reboardCovers = coversWindow(candidate.reboard());
            TimeBasis basis = reboardBasis(reach.boardable(), reboardWindow);
            Evaluation evaluation = solve(resolver -> reboardRisk(chain, included, reboardCovers, resolver), basis,
                List.of("reboard list does not cover the window"));
            List<MarginItem> items = marginsOf(reach.t1(), chain, included);
            List<ReboardBranch> branches = included.stream().map(Included::branch).toList();
            List<Attempt> attempts = branches.stream().map(ReboardBranch::boarded).toList();
            return new Outcome(evaluation, attempts, branches, minimumMargin(items), Optional.of(connectionOf(items)),
                List.of());
        }

        private List<MarginItem> marginsOf(Offset t1, List<Slot> chain, List<Included> included) {
            List<Double> chances = branchChances(chain, new Resolver(java.util.Map.of()));
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
            return items;
        }

        private Outcome emptyOrUncovered(List<StopEntry> boardable) {
            return coversWindow(candidate.opposite())
                ? new Outcome(new ConfirmedEmpty(timeBasis(boardable)), List.of(), List.of(), Optional.empty(),
                    Optional.empty(), List.of())
                : missing("opposite list does not cover the window");
        }

        private boolean returnsWithinHorizonAtBest(StopEntry entry) {
            return lowerT2(entry).compareTo(config.horizon()) <= 0;
        }

        private Offset lowerT2(StopEntry entry) {
            Offset arrival = lowerOffset(entry.arrival());
            Span ride = lowerSpan(candidate.rideTime());
            Span afterAlight = config.scaledByAlpha(lowerSpan(candidate.walkAfterAlight()));
            return config.ceilToResolution(arrival.plus(ride).plus(afterAlight));
        }

        private List<Slot> boundarySlotsOf(StopEntry entry, Offset t2, Window reboardWindow) {
            List<StopEntry> attempts = reboardWindow.attemptsFrom(t2, config.horizon());
            List<StopEntry> missed = reboardWindow.missedBefore(t2);
            return slotsOf(attempts, missed, Optional.of(entry.id()));
        }

        private List<Slot> oppositeSlots(List<StopEntry> boardable, List<StopEntry> missed, int count) {
            return slotsOf(boardable.subList(0, count), missed, Optional.empty());
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

        private TimeBasis timeBasis(List<StopEntry> boardable) {
            boolean derived = boardable.stream().anyMatch(entry -> entry.arrival().isDerived())
                || candidate.walkToOpposite().isDerived() || candidate.rideTime().isDerived()
                || candidate.walkAfterAlight().isDerived();
            return derived ? TimeBasis.DERIVED : TimeBasis.DIRECT;
        }

        private TimeBasis reboardBasis(List<StopEntry> boardable, Window reboardWindow) {
            return timeBasis(boardable) == TimeBasis.DERIVED || reboardWindow.derivedIn()
                ? TimeBasis.DERIVED : TimeBasis.DIRECT;
        }

        private Outcome missing(String what) {
            return Outcome.of(new Insufficient(List.of(what)));
        }
    }

    private record Included(StopEntry entry, Offset arrival, Offset t2, List<StopEntry> attempts,
                            List<StopEntry> missed) {
        ReboardBranch branch() {
            return new ReboardBranch(attemptOf(entry), t2, attempts.stream().map(PolicyEvaluator::attemptOf).toList());
        }
    }

    private record MarginItem(Span margin, Optional<Span> bound) {
    }

    private record Reach(Offset t1, List<StopEntry> boardable, List<StopEntry> missed) {
    }

    private record Branches(List<Included> included, List<Slot> boundarySlots) {
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

    private Evaluation solve(ToDoubleFunction<Resolver> risk, TimeBasis basis, List<String> undefinedReasons) {
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
                return new Insufficient(Stream.concat(
                    unresolved.stream().map(slot -> "probability of " + slot.attempt()), undefinedReasons.stream()).toList());
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
