package com.gustler.backend.api.salmon.domain;

import com.gustler.backend.api.salmon.domain.Candidates.*;
import com.gustler.backend.api.salmon.domain.Fields.*;
import com.gustler.backend.api.salmon.domain.Probabilities.*;
import com.gustler.backend.api.salmon.domain.StopLists.*;
import com.gustler.backend.api.salmon.domain.Timing.*;
import com.gustler.backend.api.salmon.domain.Verdicts.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 대기와 이동 후보의 탑승 실패 위험을 비교해 사유 코드 하나로 판정한다.
 *
 * <p>판정 기 한 번만 다룬다. 후보 조회가 기준선을 바꾸면 판정하지 않고 바뀐 기준선을 돌려주며,
 * 그 기준선으로 다시 판정하는 일은 {@link ActionDecider}가 맡는다. 그래서 판정 진입점은 패키지 안에만 연다.
 */
public final class ActionJudge {
    private static final double EPSILON = 1e-12;

    private ActionJudge() {
    }

    public record Baseline(String epoch, StopList currentStop, ProbabilitySupply supply) {
    }

    public sealed interface Enumeration permits Listed, Failed {
    }

    public record Listed(List<Candidate> candidates, ProbabilitySupply supply, Optional<StopList> baselineUpdate)
        implements Enumeration {
        public Listed {
            candidates = List.copyOf(candidates);
        }
    }

    public record Failed(MissingReason reason) implements Enumeration {
    }

    public interface CandidateSource {
        Enumeration enumerate(String epoch);
    }

    sealed interface Judgement permits Settled, BaselineChanged {
    }

    record Settled(Verdict verdict) implements Judgement {
    }

    record BaselineChanged(StopList update) implements Judgement {
    }

    static Judgement judgement(Baseline baseline, ActionConfig config, CandidateSource source) {
        PolicyEvaluator stayEvaluator = new PolicyEvaluator(config, baseline.epoch(), baseline.supply());
        Outcome stay = stayEvaluator.stay(baseline.currentStop());
        Optional<Double> stayRisk = stay.evaluation().failureRisk();
        if (stayRisk.isEmpty()) {
            return withoutComparison(ReasonCode.ABSTAIN_STAY_UNDEFINED, baseline, stay, List.of());
        }
        if (stayRisk.get() < config.scanThreshold() - EPSILON) {
            return withoutComparison(ReasonCode.STAY_SCAN_NOT_TRIGGERED, baseline, stay, List.of());
        }
        return switch (source.enumerate(baseline.epoch())) {
            case Failed ignored -> withoutComparison(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE, baseline, stay,
                List.of());
            case Listed listed -> listed.baselineUpdate()
                .filter(update -> changesBaseline(baseline, config, update, stay))
                .<Judgement>map(BaselineChanged::new)
                .orElseGet(() -> compare(baseline, config, stay, stayRisk.get(), listed));
        };
    }

    private static Settled compare(Baseline baseline, ActionConfig config, Outcome stay, double stayRisk,
                                   Listed listed) {
        if (listed.candidates().isEmpty()) {
            return withoutComparison(ReasonCode.NO_MOVE_NO_CANDIDATE, baseline, stay, List.of());
        }
        PolicyEvaluator evaluator = new PolicyEvaluator(config, baseline.epoch(),
            baseline.supply().plus(listed.supply()));
        List<CandidateRecord> records = new ArrayList<>();
        for (Candidate candidate : listed.candidates()) {
            Admissibility admissibility = admissibilityOf(candidate, config);
            Outcome outcome = admissibility == Admissibility.VIOLATED
                ? Outcome.of(new Insufficient(List.of(new Gap(GapKind.ADMISSIBILITY_VIOLATED, candidate.id()))))
                : evaluate(evaluator, candidate);
            records.add(new CandidateRecord(candidate.id(), candidate.kind(), admissibility, outcome));
        }
        List<CandidateRecord> permitted = records.stream()
            .filter(record -> record.admissibility() != Admissibility.VIOLATED).toList();
        if (permitted.isEmpty()) {
            return withoutComparison(ReasonCode.NO_MOVE_INFEASIBLE, baseline, stay, records);
        }
        List<CandidateRecord> comparable = permitted.stream()
            .filter(record -> record.admissibility() == Admissibility.ALLOWED
                && record.outcome().evaluation().failureRisk().isPresent()).toList();
        if (comparable.isEmpty()) {
            return withoutComparison(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE, baseline, stay, records);
        }
        boolean partial = comparable.size() < permitted.size();
        CandidateRecord best = comparable.stream().min(comparator(config, listed.candidates())).orElseThrow();
        double delta = stayRisk - best.outcome().evaluation().failureRisk().orElseThrow();
        ReasonCode code = delta >= config.gainThreshold() - EPSILON
            ? ReasonCode.GO : ReasonCode.STAY_GAIN_BELOW_THRESHOLD;
        return new Settled(new Verdict(code, baseline.epoch(), partial, stay, records, Optional.of(best.id()),
            Optional.of(delta)));
    }

    private static Outcome evaluate(PolicyEvaluator evaluator, Candidate candidate) {
        return switch (candidate) {
            case WalkCandidate walk -> evaluator.walk(walk);
            case ReboardCandidate reboard -> evaluator.reboard(reboard);
        };
    }

    private static Comparator<CandidateRecord> comparator(ActionConfig config, List<Candidate> candidates) {
        return Comparator
            .comparingDouble((CandidateRecord record) -> record.outcome().evaluation().failureRisk().orElseThrow())
            .thenComparingInt(record -> config.kindOrder().indexOf(kindOf(candidates, record.id())))
            .thenComparing(CandidateRecord::id);
    }

    private static CandidateKind kindOf(List<Candidate> candidates, String id) {
        return candidates.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow().kind();
    }

    private static boolean changesBaseline(Baseline baseline, ActionConfig config, StopList update, Outcome stay) {
        Outcome updated = new PolicyEvaluator(config, baseline.epoch(), baseline.supply()).stay(update);
        return !updated.evaluation().equals(stay.evaluation()) || !updated.attempts().equals(stay.attempts());
    }

    static Admissibility admissibilityOf(Candidate candidate, ActionConfig config) {
        List<Field<Span>> walks = switch (candidate) {
            case WalkCandidate walk -> List.of(walk.walk());
            case ReboardCandidate reboard -> List.of(reboard.walkToOpposite(), reboard.walkAfterAlight());
        };
        long knownMillis = walks.stream().map(Field::present).flatMap(Optional::stream)
            .mapToLong(Span::millis).sum();
        boolean walkViolates = knownMillis > config.maxWalk().millis();
        boolean detourViolates = candidate.detour().map(detour -> detour > config.maxDetour()).orElse(false);
        if (walkViolates || detourViolates) {
            return Admissibility.VIOLATED;
        }
        boolean complete = walks.stream().allMatch(field -> field.present().isPresent())
            && candidate.detour().isPresent();
        return complete ? Admissibility.ALLOWED : Admissibility.UNCONFIRMED;
    }

    private static Settled withoutComparison(ReasonCode code, Baseline baseline, Outcome stay,
                                             List<CandidateRecord> records) {
        return new Settled(new Verdict(code, baseline.epoch(), false, stay, records, Optional.empty(),
            Optional.empty()));
    }
}
