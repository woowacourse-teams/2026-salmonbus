package com.gustler.backend.api.salmon.domain;

import static com.gustler.backend.api.salmon.domain.Requirements.requireText;

import com.gustler.backend.api.salmon.domain.Candidates.*;
import com.gustler.backend.api.salmon.domain.Fields.*;
import com.gustler.backend.api.salmon.domain.Timing.*;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 정책 평가 결과, 판정하지 못한 이유, 사유 코드와 최종 판정을 나타낸다. */
public final class Verdicts {
    private Verdicts() {
    }

    /**
     * 실패 위험을 계산하며 쓴 가정이다. 하나도 없으면 모든 슬롯에 그 슬롯의 탑승 확률이 그대로 들어간 결과다.
     * <ul>
     *   <li>INDEP: 앞선 시도와 독립이라고 보고, 조건부 슬롯에 주변확률을 넣었다.</li>
     *   <li>SEAT_FORECAST_PROXY: 탑승 확률 대신 좌석 예보에서 옮겨 온 근사 값을 넣었다.</li>
     * </ul>
     */
    public enum Assumption { INDEP, SEAT_FORECAST_PROXY }

    public enum TimeBasis { DIRECT, DERIVED }

    public enum ConnectionTag { MARGIN_ABOVE_BOUND, MARGIN_WITHIN_BOUND, BOUND_UNKNOWN }

    public sealed interface Evaluation permits Evaluable, ConfirmedEmpty, Insufficient {
        default Optional<Double> failureRisk() {
            return switch (this) {
                case Evaluable evaluable -> Optional.of(evaluable.value());
                case ConfirmedEmpty ignored -> Optional.of(1.0);
                case Insufficient ignored -> Optional.empty();
            };
        }
    }

    public record Evaluable(double value, Set<Assumption> assumptions, TimeBasis basis) implements Evaluation {
        public Evaluable {
            EnumSet<Assumption> ordered = EnumSet.noneOf(Assumption.class);
            ordered.addAll(assumptions);
            assumptions = Collections.unmodifiableSet(ordered);
        }
    }

    public record ConfirmedEmpty(TimeBasis basis) implements Evaluation {
    }

    public record Insufficient(List<Gap> gaps) implements Evaluation {
        public Insufficient {
            gaps = List.copyOf(gaps);
        }
    }

    public enum GapKind { LIST_UNAVAILABLE, LIST_NOT_COVERING_WINDOW, ARRIVAL_UNKNOWN, TIME_UNKNOWN,
        PROBABILITY_UNKNOWN, TOO_MANY_UNKNOWN_PROBABILITIES, ADMISSIBILITY_VIOLATED }

    public enum ListRole { CURRENT, WALK, OPPOSITE, REBOARD }

    /**
     * 판정하지 못한 이유 하나. list는 이유가 걸린 목록의 역할이고, subject는 언제나 id 하나다.
     * <ul>
     *   <li>목록 종류(LIST_*): list는 그 목록의 역할, subject는 후보 id. 현재 정류장 목록이면 {@link #CURRENT_STOP}.</li>
     *   <li>ARRIVAL_UNKNOWN: list는 그 항목이 들어 있던 목록의 역할, subject는 도착을 모르는 항목 id.</li>
     *   <li>그 밖의 종류는 list가 비어 있다. PROBABILITY_UNKNOWN: 확률이 없는 시도 id. TIME_UNKNOWN,
     *       ADMISSIBILITY_VIOLATED: 후보 id. TOO_MANY_UNKNOWN_PROBABILITIES: 후보 id, 대기이면 {@link #CURRENT_STOP}.</li>
     * </ul>
     */
    public record Gap(GapKind kind, Optional<ListRole> list, String subject, Optional<MissingReason> reason) {
        public static final String CURRENT_STOP = "current-stop";

        public Gap {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(list);
            requireText(subject);
            Objects.requireNonNull(reason);
            if (list.isPresent() != concernsList(kind)) {
                throw new IllegalArgumentException("list role must be present exactly for list and arrival gaps");
            }
        }

        public Gap(GapKind kind, String subject) {
            this(kind, Optional.empty(), subject, Optional.empty());
        }

        public Gap(GapKind kind, String subject, Optional<MissingReason> reason) {
            this(kind, Optional.empty(), subject, reason);
        }

        public Gap(GapKind kind, ListRole list, String subject) {
            this(kind, Optional.of(list), subject, Optional.empty());
        }

        public Gap(GapKind kind, ListRole list, String subject, Optional<MissingReason> reason) {
            this(kind, Optional.of(list), subject, reason);
        }

        private static boolean concernsList(GapKind kind) {
            return switch (kind) {
                case LIST_UNAVAILABLE, LIST_NOT_COVERING_WINDOW, ARRIVAL_UNKNOWN -> true;
                case TIME_UNKNOWN, PROBABILITY_UNKNOWN, TOO_MANY_UNKNOWN_PROBABILITIES, ADMISSIBILITY_VIOLATED -> false;
            };
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

    public enum ReasonCode { ABSTAIN_STAY_UNDEFINED, STAY_SCAN_NOT_TRIGGERED, NO_MOVE_NO_CANDIDATE,
        NO_MOVE_INFEASIBLE, ABSTAIN_INSUFFICIENT_EVIDENCE, STAY_GAIN_BELOW_THRESHOLD, GO }

    public record CandidateRecord(String id, CandidateKind kind, Admissibility admissibility, Outcome outcome) {
    }

    /**
     * bestCandidate는 비교에서 실패 위험이 가장 낮았던 후보이고, delta는 대기 위험에서 그 후보의 위험을 뺀 값이다.
     * 이동을 권하는지는 code로만 읽는다. STAY_GAIN_BELOW_THRESHOLD에서도 bestCandidate가 있다.
     */
    public record Verdict(ReasonCode code, String epoch, boolean partialComparison, Outcome stay,
                          List<CandidateRecord> candidates, Optional<String> bestCandidate, Optional<Double> delta) {
        public Verdict {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(bestCandidate, "bestCandidate");
            Objects.requireNonNull(delta, "delta");
            candidates = List.copyOf(candidates);
            boolean compared = comparesCandidates(code);
            if (bestCandidate.isPresent() != compared || delta.isPresent() != compared) {
                throw new IllegalArgumentException(
                    "bestCandidate and delta must be present exactly for GO and STAY_GAIN_BELOW_THRESHOLD");
            }
            if (bestCandidate.isPresent()
                && candidates.stream().noneMatch(record -> record.id().equals(bestCandidate.get()))) {
                throw new IllegalArgumentException("bestCandidate must be one of the candidates");
            }
        }

        private static boolean comparesCandidates(ReasonCode code) {
            return switch (code) {
                case GO, STAY_GAIN_BELOW_THRESHOLD -> true;
                case ABSTAIN_STAY_UNDEFINED, STAY_SCAN_NOT_TRIGGERED, NO_MOVE_NO_CANDIDATE, NO_MOVE_INFEASIBLE,
                     ABSTAIN_INSUFFICIENT_EVIDENCE -> false;
            };
        }
    }
}
