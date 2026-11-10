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

/** 대기와 이동 후보의 탑승 실패 위험을 비교해 사유 코드 하나로 판정한다. */
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

    public record Judgement(Verdict verdict, Optional<StopList> staleBaseline) {
    }

    public static Verdict judge(Baseline baseline, ActionConfig config, CandidateSource source) {
        return judgement(baseline, config, source).verdict();
    }

    public static Judgement judgement(Baseline baseline, ActionConfig config, CandidateSource source) {
        PolicyEvaluator stayEvaluator = new PolicyEvaluator(config, baseline.epoch(), baseline.supply());
        Outcome stay = stayEvaluator.stay(baseline.currentStop());
        Optional<Double> stayRisk = stay.evaluation().failureRisk();
        if (stayRisk.isEmpty()) {
            return plain(ReasonCode.ABSTAIN_STAY_UNDEFINED, baseline, stay, List.of(), false);
        }
        if (stayRisk.get() < config.scanThreshold() - EPSILON) {
            return plain(ReasonCode.STAY_SCAN_NOT_TRIGGERED, baseline, stay, List.of(), false);
        }
        return switch (source.enumerate(baseline.epoch())) {
            case Failed ignored -> plain(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE, baseline, stay, List.of(), false);
            case Listed listed -> compare(baseline, config, stay, stayRisk.get(), listed);
        };
    }

    private static Judgement compare(Baseline baseline, ActionConfig config, Outcome stay, double stayRisk,
                                     Listed listed) {
        Optional<StopList> staleBaseline = listed.baselineUpdate()
            .filter(update -> changesBaseline(baseline, config, update, stay));
        if (listed.candidates().isEmpty()) {
            return stalled(ReasonCode.NO_MOVE_NO_CANDIDATE, baseline, stay, List.of(), false, staleBaseline);
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
            return stalled(ReasonCode.NO_MOVE_INFEASIBLE, baseline, stay, records, false, staleBaseline);
        }
        List<CandidateRecord> comparable = permitted.stream()
            .filter(record -> record.admissibility() == Admissibility.ALLOWED
                && record.outcome().evaluation().failureRisk().isPresent()).toList();
        if (comparable.isEmpty()) {
            return stalled(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE, baseline, stay, records, false, staleBaseline);
        }
        boolean partial = comparable.size() < permitted.size();
        CandidateRecord best = comparable.stream().min(comparator(config, listed.candidates())).orElseThrow();
        double delta = stayRisk - best.outcome().evaluation().failureRisk().orElseThrow();
        ReasonCode code = delta >= config.gainThreshold() - EPSILON
            ? ReasonCode.GO : ReasonCode.STAY_GAIN_BELOW_THRESHOLD;
        return new Judgement(new Verdict(code, baseline.epoch(), partial, stay, records, Optional.of(best.id()),
            Optional.of(delta)), staleBaseline);
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

    private static Judgement plain(ReasonCode code, Baseline baseline, Outcome stay, List<CandidateRecord> records,
                                   boolean partial) {
        return stalled(code, baseline, stay, records, partial, Optional.empty());
    }

    private static Judgement stalled(ReasonCode code, Baseline baseline, Outcome stay, List<CandidateRecord> records,
                                     boolean partial, Optional<StopList> staleBaseline) {
        return new Judgement(new Verdict(code, baseline.epoch(), partial, stay, records, Optional.empty(),
            Optional.empty()), staleBaseline);
    }
}
