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

    /** 값이 탑승 확률 자체인지(BOARDING), 좌석 예보에서 옮겨 온 근사인지(SEAT_FORECAST). */
    public enum ValueOrigin { BOARDING, SEAT_FORECAST }

    public record ProbabilityInput(String attempt, ValueTag tag, Optional<ConditionHistory> condition, double value,
                                   ValueOrigin origin) {
        public ProbabilityInput {
            requireText(attempt);
            Objects.requireNonNull(tag);
            Objects.requireNonNull(condition);
            Objects.requireNonNull(origin);
            if (!(value >= 0 && value <= 1)) {
                throw new IllegalArgumentException("Probability must be within [0, 1]");
            }
            if ((tag == ValueTag.COND_FAIL || tag == ValueTag.COND_MISS) && condition.isEmpty()) {
                throw new IllegalArgumentException("Condition must be present for COND_FAIL and COND_MISS");
            }
        }
    }

    public sealed interface SlotValue permits Matched, Marginal, Absent {
    }

    public record Matched(double value, ValueOrigin origin) implements SlotValue {
    }

    public record Marginal(double value, ValueOrigin origin) implements SlotValue {
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

        /**
         * 슬롯과 조건 이력이 같은 값을 먼저 쓰고, 없을 때만 조건 이력 없는 주변확률을 쓴다. 조건 이력이 다른 값은 쓰지 않는다.
         * 재탑승 가지는 맞은편에서 탄 차가 조건 이력에 들어 있어서, 한 가지의 값이 다른 가지 슬롯에 들어가지 않는다.
         */
        public SlotValue valueFor(Slot slot) {
            List<ProbabilityInput> sameCondition = inputs.stream()
                .filter(input -> input.attempt().equals(slot.attempt())
                    && input.condition().filter(slot.condition()::equals).isPresent())
                .toList();
            Optional<ProbabilityInput> exact = sameCondition.stream()
                .filter(input -> input.tag() == exactTagOf(slot.kind())).findFirst();
            if (exact.isPresent()) {
                return new Matched(exact.get().value(), exact.get().origin());
            }
            Optional<ProbabilityInput> marginal = sameCondition.stream()
                .filter(input -> input.tag() == ValueTag.MARG).findFirst()
                .or(() -> inputs.stream()
                    .filter(input -> input.attempt().equals(slot.attempt()) && input.tag() == ValueTag.MARG
                        && input.condition().isEmpty())
                    .findFirst());
            boolean marginalIsExact = slot.kind() == SlotKind.S_FIRST;
            return marginal
                .<SlotValue>map(input -> marginalIsExact
                    ? new Matched(input.value(), input.origin())
                    : new Marginal(input.value(), input.origin()))
                .orElse(new Absent());
        }

        private static ValueTag exactTagOf(SlotKind kind) {
            return switch (kind) {
                case S_FIRST -> ValueTag.MARG;
                case S_MISS -> ValueTag.COND_MISS;
                case S_FAIL -> ValueTag.COND_FAIL;
            };
        }
    }
}
