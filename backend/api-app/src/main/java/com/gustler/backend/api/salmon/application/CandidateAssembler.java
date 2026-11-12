package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.salmon.application.WalkOption.Relation;
import com.gustler.backend.api.salmon.domain.ActionJudge.Listed;
import com.gustler.backend.api.salmon.domain.Candidates.Candidate;
import com.gustler.backend.api.salmon.domain.Candidates.WalkCandidate;
import com.gustler.backend.api.salmon.domain.Fields.Observed;
import com.gustler.backend.api.salmon.domain.Probabilities.ProbabilitySupply;
import com.gustler.backend.api.salmon.domain.Timing.Span;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** SAL-362 탑승 후보 중 같은 방향 후보를 직접 도보 후보로 조립한다. */
public final class CandidateAssembler {
    private static final Set<Relation> SAME_DIRECTION = Set.of(Relation.BEFORE, Relation.AFTER);

    private CandidateAssembler() {
    }

    /** 직접 도보로 조립하는 관계. 탑승 후보 조회도 이 집합만 요청한다. */
    static Set<Relation> sameDirection() {
        return SAME_DIRECTION;
    }

    public static Listed assemble(String routeId, Instant decisionAt, List<WalkOption> options,
                                StopArrivalSource arrivals, DetourSource detours, ProbabilitySupply supply) {
        List<Candidate> candidates = options.stream()
            .filter(option -> SAME_DIRECTION.contains(option.relation()))
            .<Candidate>map(option -> new WalkCandidate(idOf(option),
                new Observed<>(new Span(option.walkSeconds() * 1000L)),
                arrivals.arrivalsAt(routeId, option.stationId(), option.stopSequence(), decisionAt),
                detours.detourOf(routeId, option)))
            .toList();
        return new Listed(candidates, supply, Optional.empty());
    }

    static String idOf(WalkOption option) {
        return "walk-" + option.stationId() + "-" + option.stopSequence();
    }
}
