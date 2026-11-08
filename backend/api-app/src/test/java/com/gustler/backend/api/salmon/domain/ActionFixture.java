package com.gustler.backend.api.salmon.domain;

import com.gustler.backend.api.salmon.domain.ActionJudge.Baseline;
import com.gustler.backend.api.salmon.domain.ActionJudge.Enumeration;
import com.gustler.backend.api.salmon.domain.ActionJudge.Listed;
import com.gustler.backend.api.salmon.domain.ActionModel.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ActionFixture {
    public static final String EPOCH = "epoch-1";

    public List<ProbabilityInput> stayProbabilities = new ArrayList<>();
    public List<ProbabilityInput> candidateProbabilities = new ArrayList<>();
    public StopList stay = queried(entry("s0-3", 3), entry("s0-11", 11));
    public StopList opposite = queried(entry("o-4", 4), entry("o-9", 9));
    public StopList reboard = queried(entry("b-6", 6), entry("b-14", 14), entry("b-19", 19));
    public Field<Span> walkToOpposite = observed(2);
    public Field<Span> ride = new Derived<>(minutes(5), new NoBound());
    public Field<Span> walkAfterAlight = observed(3);
    public Optional<Double> detour = Optional.of(0.0);

    public ActionFixture() {
        stayProbabilities.add(marginal("s0-3", 0.05));
        stayProbabilities.add(conditional("s0-11", ValueTag.COND_FAIL, 0.10, Set.of("s0-3"), Set.of(), Optional.empty()));
        candidateProbabilities.add(marginal("o-4", 0.8));
        candidateProbabilities.add(conditional("o-9", ValueTag.COND_FAIL, 0.8, Set.of("o-4"), Set.of(), Optional.empty()));
        candidateProbabilities.add(conditional("b-14", ValueTag.COND_MISS, 0.35, Set.of(), Set.of("b-6"), Optional.of("o-4")));
        candidateProbabilities.add(conditional("b-19", ValueTag.COND_FAIL, 0.30, Set.of("b-14"), Set.of("b-6"), Optional.of("o-4")));
        candidateProbabilities.add(conditional("b-19", ValueTag.COND_MISS, 0.35, Set.of(), Set.of("b-6", "b-14"), Optional.of("o-9")));
        candidateProbabilities.add(conditional("wB-14", ValueTag.COND_MISS, 0.35, Set.of(), Set.of("wB-6"), Optional.empty()));
        candidateProbabilities.add(conditional("wF-13", ValueTag.COND_MISS, 0.03, Set.of(), Set.of("wF-5"), Optional.empty()));
    }

    public static ActionConfig config(double horizonMinutes, double maxWalkMinutes) {
        return new ActionConfig(0.40, 0.15, new BigDecimal("1.2"), Offset.ofMinutes(horizonMinutes),
            Span.ofMinutes(maxWalkMinutes), 100, new Span(1), 3, List.of(CandidateKind.WALK, CandidateKind.REBOARD));
    }

    public static Offset offsetOf(double minutes) {
        return Offset.ofMinutes(minutes);
    }

    public static Span minutes(double minutes) {
        return Span.ofMinutes(minutes);
    }

    public static Field<Span> observed(double minutes) {
        return new Observed<>(minutes(minutes));
    }

    public static StopEntry entry(String id, double arrivalMinutes) {
        return new StopEntry(id, new Observed<>(offsetOf(arrivalMinutes)));
    }

    public static Queried queried(StopEntry... entries) {
        return new Queried(List.of(entries), Coverage.COVERS_WINDOW);
    }

    public static ProbabilityInput marginal(String attempt, double value) {
        return new ProbabilityInput(attempt, ValueTag.MARG, Optional.empty(), value);
    }

    public static ProbabilityInput conditional(String attempt, ValueTag tag, double value, Set<String> failed,
                                        Set<String> skipped, Optional<String> boarded) {
        return new ProbabilityInput(attempt, tag,
            Optional.of(new ConditionHistory(EPOCH, failed, skipped, boarded)), value);
    }

    public ReboardCandidate reboardCandidate() {
        return new ReboardCandidate("reboard", walkToOpposite, ride, walkAfterAlight, opposite, reboard, detour);
    }

    public WalkCandidate walkB() {
        return new WalkCandidate("walk-B", observed(9), queried(entry("wB-6", 6), entry("wB-14", 14)), detour);
    }

    public WalkCandidate walkF() {
        return new WalkCandidate("walk-F", observed(10), queried(entry("wF-5", 5), entry("wF-13", 13)), detour);
    }

    public Baseline baseline() {
        return new Baseline(EPOCH, stay, new ProbabilitySupply(stayProbabilities));
    }

    public ActionJudge.CandidateSource source(Candidate... candidates) {
        Listed listed = new Listed(List.of(candidates), new ProbabilitySupply(candidateProbabilities), Optional.empty());
        return epoch -> listed;
    }

    public static ActionJudge.CandidateSource sourceOf(Enumeration enumeration) {
        return epoch -> enumeration;
    }

    public static ActionJudge.CandidateSource neverCalled() {
        return epoch -> {
            throw new AssertionError("candidate source must not be called");
        };
    }
}
