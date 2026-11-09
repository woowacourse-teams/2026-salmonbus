package com.gustler.backend.api.salmon.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ActionModel {
    private ActionModel() {
    }

    public record Offset(long millis) implements Comparable<Offset> {
        public static Offset ofMinutes(double minutes) {
            return new Offset(Math.round(minutes * 60_000));
        }

        public Offset plus(Span span) {
            return new Offset(millis + span.millis());
        }

        public Span until(Offset later) {
            return new Span(later.millis - millis);
        }

        public double minutes() {
            return millis / 60_000.0;
        }

        @Override
        public int compareTo(Offset other) {
            return Long.compare(millis, other.millis);
        }
    }

    public record Span(long millis) implements Comparable<Span> {
        public Span {
            if (millis < 0) {
                throw new IllegalArgumentException("Span must not be negative");
            }
        }

        public static Span ofMinutes(double minutes) {
            return new Span(Math.round(minutes * 60_000));
        }

        public Span plus(Span other) {
            return new Span(millis + other.millis);
        }

        public double minutes() {
            return millis / 60_000.0;
        }

        @Override
        public int compareTo(Span other) {
            return Long.compare(millis, other.millis);
        }
    }

    public enum MissingReason { QUERY_FAILED, NOT_PROVIDED, STALE, UNIDENTIFIED_VEHICLE, UNVERIFIED_SEQUENCE,
        COVER_BROKEN, NO_ERROR_BOUND, PREDICTION_CONFLICT, INFORMATION_TIME_MISMATCH, BASELINE_UNSTABLE,
        BUDGET_EXHAUSTED }

    public sealed interface ErrorBound permits Finite, NoBound {
    }

    public record Finite(Span bound) implements ErrorBound {
    }

    public record NoBound() implements ErrorBound {
    }

    public sealed interface Field<T> permits Observed, Derived, Missing {
        default Optional<T> present() {
            return switch (this) {
                case Observed<T> observed -> Optional.of(observed.value());
                case Derived<T> derived -> Optional.of(derived.value());
                case Missing<T> ignored -> Optional.empty();
            };
        }

        default boolean isDerived() {
            return this instanceof Derived<T>;
        }

        default Optional<Span> knownBound() {
            return switch (this) {
                case Observed<T> observed -> Optional.of(observed.quantization().orElse(new Span(0)));
                case Derived<T> derived -> derived.bound() instanceof Finite finite
                    ? Optional.of(finite.bound()) : Optional.empty();
                case Missing<T> ignored -> Optional.empty();
            };
        }
    }

    public record Observed<T>(T value, Optional<Span> quantization) implements Field<T> {
        public Observed {
            Objects.requireNonNull(value);
            Objects.requireNonNull(quantization);
        }

        public Observed(T value) {
            this(value, Optional.empty());
        }
    }

    public record Derived<T>(T value, ErrorBound bound) implements Field<T> {
        public Derived {
            Objects.requireNonNull(value);
            Objects.requireNonNull(bound);
        }
    }

    public record Missing<T>(MissingReason reason) implements Field<T> {
        public Missing {
            Objects.requireNonNull(reason);
        }
    }

    public record StopEntry(String id, Field<Offset> arrival) {
        public StopEntry {
            requireText(id);
            Objects.requireNonNull(arrival);
        }
    }

    public enum Coverage { COVERS_WINDOW, COVERAGE_UNKNOWN }

    public sealed interface StopList permits Queried, RestoredOnly, Unavailable {
        List<StopEntry> entries();
    }

    public record Queried(List<StopEntry> entries, Coverage coverage) implements StopList {
        public Queried {
            entries = List.copyOf(entries);
            Objects.requireNonNull(coverage);
        }
    }

    public record RestoredOnly(List<StopEntry> entries) implements StopList {
        public RestoredOnly {
            entries = List.copyOf(entries);
        }
    }

    public record Unavailable(MissingReason reason) implements StopList {
        public Unavailable {
            Objects.requireNonNull(reason);
        }

        @Override
        public List<StopEntry> entries() {
            return List.of();
        }
    }

    public enum ValueTag { COND_FAIL, COND_MISS, MARG, UNDIFF }

    public enum SlotKind { S_FIRST, S_MISS, S_FAIL }

    public record ConditionHistory(String epoch, Set<String> priorFailed, Set<String> notAttempted,
                                   Optional<String> oppositeBoarded) {
        public ConditionHistory {
            requireText(epoch);
            priorFailed = Set.copyOf(priorFailed);
            notAttempted = Set.copyOf(notAttempted);
            Objects.requireNonNull(oppositeBoarded);
        }
    }

    public record Slot(String attempt, SlotKind kind, ConditionHistory condition) {
        public Slot {
            requireText(attempt);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(condition);
        }
    }

    public record ProbabilityInput(String attempt, ValueTag tag, Optional<ConditionHistory> condition, double value) {
        public ProbabilityInput {
            requireText(attempt);
            Objects.requireNonNull(tag);
            Objects.requireNonNull(condition);
            if (!(value >= 0 && value <= 1)) {
                throw new IllegalArgumentException("Probability must be within [0, 1]");
            }
        }
    }

    public sealed interface SlotValue permits Matched, Marginal, Absent {
    }

    public record Matched(double value) implements SlotValue {
    }

    public record Marginal(double value) implements SlotValue {
    }

    public record Absent() implements SlotValue {
    }

    public record ProbabilitySupply(List<ProbabilityInput> inputs) {
        public ProbabilitySupply {
            inputs = List.copyOf(inputs);
        }

        public static ProbabilitySupply none() {
            return new ProbabilitySupply(List.of());
        }

        public ProbabilitySupply plus(ProbabilitySupply other) {
            return new ProbabilitySupply(java.util.stream.Stream.concat(inputs.stream(),
                other.inputs.stream()).toList());
        }

        public SlotValue valueFor(Slot slot) {
            Optional<SlotValue> exact = inputs.stream()
                .filter(input -> input.attempt().equals(slot.attempt()) && exactlyFits(input, slot))
                .findFirst().map(input -> new Matched(input.value()));
            if (exact.isPresent()) {
                return exact.get();
            }
            boolean marginalAllowed = slot.kind() != SlotKind.S_FIRST;
            return inputs.stream()
                .filter(input -> input.attempt().equals(slot.attempt()) && input.tag() == ValueTag.MARG)
                .findFirst()
                .<SlotValue>map(input -> marginalAllowed ? new Marginal(input.value()) : new Matched(input.value()))
                .orElse(new Absent());
        }

        private static boolean exactlyFits(ProbabilityInput input, Slot slot) {
            return switch (slot.kind()) {
                case S_FIRST -> input.tag() == ValueTag.MARG
                    && input.condition().map(slot.condition()::equals).orElse(true);
                case S_MISS -> input.tag() == ValueTag.COND_MISS
                    && input.condition().map(slot.condition()::equals).orElse(false);
                case S_FAIL -> input.tag() == ValueTag.COND_FAIL
                    && input.condition().map(slot.condition()::equals).orElse(false);
            };
        }
    }

    public enum Assumption { EXACT, INDEP }

    public enum TimeBasis { DIRECT, DERIVED }

    public enum ConnectionTag { MARGIN_ABOVE_BOUND, MARGIN_WITHIN_BOUND, BOUND_UNKNOWN }

    public enum Admissibility { VIOLATED, ALLOWED, UNCONFIRMED }

    public sealed interface Evaluation permits Evaluable, ConfirmedEmpty, Insufficient {
        default Optional<Double> failureRisk() {
            return switch (this) {
                case Evaluable evaluable -> Optional.of(evaluable.value());
                case ConfirmedEmpty ignored -> Optional.of(1.0);
                case Insufficient ignored -> Optional.empty();
            };
        }
    }

    public record Evaluable(double value, Assumption assumption, TimeBasis basis) implements Evaluation {
    }

    public record ConfirmedEmpty(TimeBasis basis) implements Evaluation {
    }

    public record Insufficient(List<String> missing) implements Evaluation {
        public Insufficient {
            missing = List.copyOf(missing);
        }
    }

    public record Attempt(String entryId, Offset arrival) {
    }

    public record ReboardBranch(Attempt boarded, Offset ready, List<Attempt> reboards) {
        public ReboardBranch {
            Objects.requireNonNull(boarded);
            Objects.requireNonNull(ready);
            reboards = List.copyOf(reboards);
        }
    }

    public record Outcome(Evaluation evaluation, List<Attempt> attempts, List<ReboardBranch> branches,
                          Optional<Span> margin, Optional<ConnectionTag> connection, List<String> nearMissExcluded) {
        public Outcome {
            attempts = List.copyOf(attempts);
            branches = List.copyOf(branches);
            nearMissExcluded = List.copyOf(nearMissExcluded);
            if (!branches.isEmpty() && !attempts.equals(branches.stream().map(ReboardBranch::boarded).toList())) {
                throw new IllegalArgumentException("reboard attempts must match the boarded bus of each branch");
            }
        }

        static Outcome of(Evaluation evaluation) {
            return new Outcome(evaluation, List.of(), List.of(), Optional.empty(), Optional.empty(), List.of());
        }
    }

    public enum CandidateKind { WALK, REBOARD }

    public sealed interface Candidate permits WalkCandidate, ReboardCandidate {
        String id();

        CandidateKind kind();

        Optional<Double> detour();
    }

    public record WalkCandidate(String id, Field<Span> walk, StopList list, Optional<Double> detour)
        implements Candidate {
        public WalkCandidate {
            requireText(id);
            Objects.requireNonNull(walk);
            Objects.requireNonNull(list);
            Objects.requireNonNull(detour);
        }

        @Override
        public CandidateKind kind() {
            return CandidateKind.WALK;
        }
    }

    public record ReboardCandidate(String id, Field<Span> walkToOpposite, Field<Span> rideTime,
                                   Field<Span> walkAfterAlight, StopList opposite, StopList reboard,
                                   Optional<Double> detour) implements Candidate {
        public ReboardCandidate {
            requireText(id);
            Objects.requireNonNull(walkToOpposite);
            Objects.requireNonNull(rideTime);
            Objects.requireNonNull(walkAfterAlight);
            Objects.requireNonNull(opposite);
            Objects.requireNonNull(reboard);
            Objects.requireNonNull(detour);
        }

        @Override
        public CandidateKind kind() {
            return CandidateKind.REBOARD;
        }
    }

    public enum ReasonCode { ABSTAIN_STAY_UNDEFINED, STAY_SCAN_NOT_TRIGGERED, NO_MOVE_NO_CANDIDATE,
        NO_MOVE_INFEASIBLE, ABSTAIN_INSUFFICIENT_EVIDENCE, STAY_GAIN_BELOW_THRESHOLD, GO }

    public record CandidateRecord(String id, CandidateKind kind, Admissibility admissibility, Outcome outcome) {
    }

    public record Verdict(ReasonCode code, String epoch, boolean partialComparison, Outcome stay,
                          List<CandidateRecord> candidates, Optional<String> chosen, Optional<Double> delta) {
        public Verdict {
            candidates = List.copyOf(candidates);
        }
    }

    static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Identifier must not be blank");
        }
    }
}
