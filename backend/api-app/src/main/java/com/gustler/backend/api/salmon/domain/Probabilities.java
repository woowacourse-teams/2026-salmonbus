package com.gustler.backend.api.salmon.domain;

import static com.gustler.backend.api.salmon.domain.Requirements.requireText;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** 탑승 확률 입력과, 시도 슬롯에 맞는 값을 고르는 규칙을 모은다. */
public final class Probabilities {
    private Probabilities() {
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
            return new ProbabilitySupply(Stream.concat(inputs.stream(),
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
}
