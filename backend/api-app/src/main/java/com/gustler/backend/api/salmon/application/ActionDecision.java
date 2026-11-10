package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.boarding.application.BoardingOptionsQuery;
import com.gustler.backend.api.boarding.application.BoardingOptionsQueryService;
import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.salmon.application.WalkOption.Relation;
import com.gustler.backend.api.salmon.domain.ActionConfig;
import com.gustler.backend.api.salmon.domain.ActionDecider;
import com.gustler.backend.api.salmon.domain.ActionDecider.Decision;
import com.gustler.backend.api.salmon.domain.ActionDecider.Sources;
import com.gustler.backend.api.salmon.domain.ActionJudge.Baseline;
import com.gustler.backend.api.salmon.domain.ActionJudge.CandidateSource;
import com.gustler.backend.api.salmon.domain.ActionJudge.Enumeration;
import com.gustler.backend.api.salmon.domain.ActionJudge.Listed;
import com.gustler.backend.api.salmon.domain.Candidates.Candidate;
import com.gustler.backend.api.salmon.domain.Candidates.WalkCandidate;
import com.gustler.backend.api.salmon.domain.Probabilities.ProbabilitySupply;
import com.gustler.backend.api.salmon.domain.StopLists.StopEntry;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 제품 판정의 호출부. 판정은 {@link ActionDecider#decide}만 호출한다.
 * 하차 구간은 호출자가 넘긴 목록만 조립하고, 규칙이 없으면 빈 목록이다.
 */
public final class ActionDecision {
    private final BoardingOptionsQueryService boarding;
    private final StopArrivalSource arrivals;
    private final DetourSource detours;
    private final SeatForecastProbabilitySource probabilities;

    public ActionDecision(BoardingOptionsQueryService boarding, StopArrivalSource arrivals, DetourSource detours,
                          SeatForecastProbabilitySource probabilities) {
        this.boarding = Objects.requireNonNull(boarding);
        this.arrivals = Objects.requireNonNull(arrivals);
        this.detours = Objects.requireNonNull(detours);
        this.probabilities = Objects.requireNonNull(probabilities);
    }

    public Decision decide(String routeId, String stationId, int stopSequence, Instant decisionAt,
                           ActionConfig config, List<AlightingLeg> legs) {
        Objects.requireNonNull(decisionAt, "decisionAt");
        Objects.requireNonNull(config, "config");
        List<AlightingLeg> chosen = List.copyOf(legs);
        Map<String, ProbabilitySupply> supplies = new HashMap<>();
        StopList current = arrivals.arrivalsAt(routeId, stationId, stopSequence, decisionAt);
        ProbabilitySupply currentSupply = supplyOf(supplies, routeId, stationId, stopSequence, current, decisionAt);
        String currentKey = supplyKey(stationId, stopSequence, current);
        return ActionDecider.decide(new Sources() {
            @Override
            public Baseline baseline(String epoch, Optional<StopList> override) {
                return new Baseline(epoch, override.orElse(current), currentSupply);
            }

            @Override
            public CandidateSource candidates() {
                return epoch -> candidatesOf(routeId, stationId, stopSequence, decisionAt, chosen, supplies, currentKey);
            }
        }, config);
    }

    private Enumeration candidatesOf(String routeId, String stationId, int stopSequence, Instant decisionAt,
                                     List<AlightingLeg> legs, Map<String, ProbabilitySupply> supplies,
                                     String currentKey) {
        List<WalkOption> options = WalkOptions.from(boarding.get(walkQuery(routeId, stationId, stopSequence)));
        Listed walks = CandidateAssembler.assemble(routeId, decisionAt, options, arrivals, detours,
            ProbabilitySupply.none());
        Map<String, WalkOption> optionById = new HashMap<>();
        for (WalkOption option : options) {
            optionById.put(CandidateAssembler.idOf(option), option);
        }
        ProbabilitySupply supply = ProbabilitySupply.none();
        Set<String> included = new HashSet<>();
        included.add(currentKey);
        for (Candidate candidate : walks.candidates()) {
            if (!(candidate instanceof WalkCandidate walk)) {
                throw new IllegalStateException("walk assembly returned " + candidate.kind());
            }
            WalkOption option = optionById.get(walk.id());
            if (option == null) {
                throw new IllegalStateException("walk candidate has no option: " + walk.id());
            }
            supply = include(supply, included, supplies, routeId, option.stationId(), option.stopSequence(),
                walk.list(), decisionAt);
        }
        List<Candidate> reboards = new ArrayList<>();
        for (AlightingLeg leg : legs) {
            StopList opposite = arrivals.arrivalsAt(routeId, leg.oppositeStationId(), leg.oppositeSequence(),
                decisionAt);
            StopList reboard = arrivals.arrivalsAt(routeId, leg.reboardStationId(), leg.reboardSequence(), decisionAt);
            reboards.add(ReboardAssembly.candidate(leg, opposite, reboard, Optional.empty()));
            supply = include(supply, included, supplies, routeId, leg.oppositeStationId(), leg.oppositeSequence(),
                opposite, decisionAt);
            supply = include(supply, included, supplies, routeId, leg.reboardStationId(), leg.reboardSequence(),
                reboard, decisionAt);
        }
        List<Candidate> candidates = new ArrayList<>(walks.candidates());
        candidates.addAll(reboards);
        return new Listed(candidates, supply, walks.baselineUpdate());
    }

    private ProbabilitySupply include(ProbabilitySupply supply, Set<String> included,
                                      Map<String, ProbabilitySupply> supplies, String routeId, String stationId,
                                      int stopSequence, StopList list, Instant decisionAt) {
        if (!included.add(supplyKey(stationId, stopSequence, list))) {
            return supply;
        }
        return supply.plus(supplyOf(supplies, routeId, stationId, stopSequence, list, decisionAt));
    }

    private ProbabilitySupply supplyOf(Map<String, ProbabilitySupply> supplies, String routeId, String stationId,
                                       int stopSequence, StopList list, Instant decisionAt) {
        return supplies.computeIfAbsent(supplyKey(stationId, stopSequence, list),
            ignored -> probabilities.probabilitiesFor(routeId, stationId, stopSequence, list, decisionAt));
    }

    private static String supplyKey(String stationId, int stopSequence, StopList list) {
        return stationId + "\n" + stopSequence + "\n" + list.entries().stream().map(StopEntry::id)
            .collect(Collectors.joining("\n"));
    }

    private static BoardingOptionsQuery walkQuery(String routeId, String stationId, int stopSequence) {
        EnumSet<StopRelation> relations = EnumSet.noneOf(StopRelation.class);
        for (Relation relation : CandidateAssembler.sameDirection()) {
            relations.add(stopRelation(relation));
        }
        return new BoardingOptionsQuery(new RouteId(routeId), stationId, stopSequence, null, Set.of(AccessMode.WALK),
            BoardingOptionsQuery.DEFAULT_MAX_MINUTES, BoardingOptionsQuery.DEFAULT_LIMIT, relations, true);
    }

    private static StopRelation stopRelation(Relation relation) {
        return switch (relation) {
            case BEFORE -> StopRelation.BEFORE;
            case AFTER -> StopRelation.AFTER;
            case OPPOSITE, SAME_STATION, UNKNOWN -> throw new IllegalStateException(
                "same-direction assembly contains " + relation);
        };
    }
}
