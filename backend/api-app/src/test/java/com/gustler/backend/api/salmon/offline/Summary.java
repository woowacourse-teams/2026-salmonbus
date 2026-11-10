package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.Found;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.Lookup;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.NotFound;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import com.gustler.backend.api.salmon.domain.StopLists.Unavailable;
import com.gustler.backend.api.salmon.domain.Verdicts.Assumption;
import com.gustler.backend.api.salmon.domain.Verdicts.ConfirmedEmpty;
import com.gustler.backend.api.salmon.domain.Verdicts.Evaluable;
import com.gustler.backend.api.salmon.domain.Verdicts.Evaluation;
import com.gustler.backend.api.salmon.domain.Verdicts.Gap;
import com.gustler.backend.api.salmon.domain.Verdicts.Insufficient;
import com.gustler.backend.api.salmon.offline.PassOutcome.Seats;
import com.gustler.backend.api.salmon.offline.StayRun.BoardCheck;
import com.gustler.backend.api.salmon.offline.StayRun.BoardVehicle;
import com.gustler.backend.api.salmon.offline.StayRun.DecisionRecord;
import com.gustler.backend.api.salmon.offline.StayRun.EntryRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 판정 기록을 세어 결과 파일의 results 블록을 만든다. 차량 ID와 항목 ID는 담지 않는다. */
final class Summary {
    private static final List<Double> QUANTILES = List.of(0.10, 0.25, 0.50, 0.75, 0.90);

    private int decisions;
    private int routeIdsDiffer;
    private final Map<String, Integer> reasonCodes = new TreeMap<>();
    private final Map<String, Integer> arrivalLists = new TreeMap<>();
    private final Map<String, Integer> entriesPerList = new TreeMap<>();
    private final Map<String, Integer> attemptsPerDecision = new TreeMap<>();
    private final Map<String, Integer> stayEvaluations = new TreeMap<>();
    private final Map<List<String>, Integer> stayGaps = new TreeMap<>(Summary::compare);
    private final Map<String, Integer> assumptions = new TreeMap<>();
    private final List<Double> stayRisks = new ArrayList<>();
    private final Map<String, Integer> boards = new TreeMap<>();
    private final Map<String, Integer> boardVehicles = new TreeMap<>();
    private final Map<String, Integer> boardForecasts = new TreeMap<>();
    private final OutcomeTally attemptOutcomes = new OutcomeTally();
    private final Map<String, Integer> stayByFirst = new TreeMap<>();
    private final Map<String, Integer> stayByLast = new TreeMap<>();
    private final Map<String, Integer> stayByWindow = new TreeMap<>();
    private final FailureByRisk failureByRiskFirst = new FailureByRisk();
    private final FailureByRisk failureByRiskLast = new FailureByRisk();
    private final FailureByRisk failureByRiskWindow = new FailureByRisk();
    private final OutcomeTally.Pairs pairsByFirst = new OutcomeTally.Pairs();
    private final OutcomeTally.Pairs pairsByLast = new OutcomeTally.Pairs();
    private final OutcomeTally boardVehicleOutcomes = new OutcomeTally();
    private final OutcomeTally.Pairs boardPairsByFirst = new OutcomeTally.Pairs();
    private final OutcomeTally.Pairs boardPairsByLast = new OutcomeTally.Pairs();
    private final Set<String> boardVehicleStops = new HashSet<>();
    private final LookupCounts allEntries = new LookupCounts();
    private final LookupCounts windowAttempts = new LookupCounts();

    void add(DecisionRecord decision) {
        decisions++;
        if (decision.routeIdsDiffer()) {
            routeIdsDiffer++;
        }
        count(reasonCodes, decision.verdict().code().name());
        count(arrivalLists, listStateOf(decision.list()));
        count(entriesPerList, Integer.toString(decision.entries().size()));
        count(attemptsPerDecision, Integer.toString(decision.verdict().stay().attempts().size()));
        Evaluation stay = decision.verdict().stay().evaluation();
        count(stayEvaluations, evaluationOf(stay));
        if (stay instanceof Insufficient insufficient) {
            insufficient.gaps().forEach(gap -> stayGaps.merge(keyOf(gap), 1, Integer::sum));
        }
        if (stay instanceof Evaluable evaluable) {
            count(assumptions, assumptionsOf(evaluable));
            stayRisks.add(evaluable.value());
        }
        count(boards, decision.board().status());
        if (decision.board().read()) {
            count(boardVehicles, Integer.toString(decision.board().vehicles()));
            count(boardForecasts, Integer.toString(decision.board().forecasts()));
        }
        addOutcomes(decision, stay);
        for (EntryRecord entry : decision.entries()) {
            allEntries.add(entry);
            if (entry.attempted()) {
                windowAttempts.add(entry);
            }
        }
    }

    private void addOutcomes(DecisionRecord decision, Evaluation stay) {
        List<EntryRecord> attempts = decision.entries().stream().filter(EntryRecord::attempted).toList();
        attempts.forEach(attempt -> attemptOutcomes.add(attempt.chance(), attempt.outcome()));
        String byFirst = stayByFirstOf(decision);
        String byLast = stayByLastOf(decision);
        String byWindow = stayByWindowOf(decision);
        count(stayByFirst, byFirst);
        count(stayByLast, byLast);
        count(stayByWindow, byWindow);
        if (stay instanceof Evaluable evaluable) {
            failureByRiskFirst.add(evaluable.value(), attempts.stream().map(a -> a.outcome().first()).toList());
            failureByRiskLast.add(evaluable.value(), attempts.stream().map(a -> a.outcome().last()).toList());
            failureByRiskWindow.add(evaluable.value(), decision.windowPassages());
        }
        if (attempts.size() >= 2) {
            EntryRecord front = attempts.get(0);
            EntryRecord back = attempts.get(1);
            pairsByFirst.add(front.chance(), front.outcome().first(), back.chance(), back.outcome().first());
            pairsByLast.add(front.chance(), front.outcome().last(), back.chance(), back.outcome().last());
        }
        List<BoardVehicle> vehicles = decision.board().forecastVehicles();
        for (BoardVehicle vehicle : vehicles) {
            boardVehicleOutcomes.add(Optional.of(vehicle.chance()), vehicle.outcome());
            boardVehicleStops.add(vehicle.vehicleKey());
        }
        if (vehicles.size() >= 2) {
            BoardVehicle front = vehicles.get(0);
            BoardVehicle back = vehicles.get(1);
            boardPairsByFirst.add(Optional.of(front.chance()), front.outcome().first(), Optional.of(back.chance()),
                back.outcome().first());
            boardPairsByLast.add(Optional.of(front.chance()), front.outcome().last(), Optional.of(back.chance()),
                back.outcome().last());
        }
    }

    /**
     * 예측한 대기 실패 위험 구간별 실제 실패율. 차량의 결과를 전부 아는 판정만 구간에 넣는다.
     *
     * <p>성공은 한 대만 빈자리여도 정해지고 실패는 전부 만석이어야 정해진다. 일부만 아는 판정을 넣으면 "빈자리와 모름"은
     * 성공으로 들어가고 "만석과 모름"은 빠져서 실패율이 낮게 나온다. 그래서 일부만 아는 판정은 빼고 그 수를 따로 낸다.
     */
    private static final class FailureByRisk {
        private final OutcomeTally.Bins bins = new OutcomeTally.Bins();
        private final Map<String, Integer> excluded = new TreeMap<>();
        private int included;

        void add(double risk, List<Seats> vehicles) {
            if (vehicles.isEmpty()) {
                count(excluded, "NO_VEHICLE");
            } else if (!vehicles.stream().allMatch(Seats::known)) {
                boolean seatsSeen = vehicles.stream().anyMatch(seats -> seats.known() && !seats.full());
                count(excluded, seatsSeen ? "SEATS_AVAILABLE_AND_UNKNOWN" : "FULL_OR_UNKNOWN_ONLY");
            } else {
                included++;
                bins.add(risk, vehicles.stream().allMatch(Seats::full));
            }
        }

        Map<String, Object> counts() {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("decisionsWithAllVehiclesKnown", included);
            counts.put("excludedDecisions", excluded);
            counts.put("bins", bins.rows("failed"));
            return counts;
        }
    }

    static String stayByFirstOf(DecisionRecord decision) {
        return OutcomeTally.stayOf(decision.entries().stream().filter(EntryRecord::attempted)
            .map(entry -> entry.outcome().first()).toList(), "NO_ATTEMPT");
    }

    static String stayByLastOf(DecisionRecord decision) {
        return OutcomeTally.stayOf(decision.entries().stream().filter(EntryRecord::attempted)
            .map(entry -> entry.outcome().last()).toList(), "NO_ATTEMPT");
    }

    static String stayByWindowOf(DecisionRecord decision) {
        return OutcomeTally.stayOf(decision.windowPassages(), "NO_PASSAGE");
    }

    /** 판정 시각 뒤의 차량 관측으로 낸 실제 결과 대조. */
    Map<String, Object> outcomes() {
        Map<String, Object> outcomes = new LinkedHashMap<>();
        outcomes.put("attempts", attemptOutcomes.counts());
        Map<String, Object> byAttempts = new LinkedHashMap<>();
        byAttempts.put("byFirstObservation", stayByFirst);
        byAttempts.put("byLastObservation", stayByLast);
        byAttempts.put("failureByRiskByFirstObservation", failureByRiskFirst.counts());
        byAttempts.put("failureByRiskByLastObservation", failureByRiskLast.counts());
        outcomes.put("stayByAttempts", byAttempts);
        Map<String, Object> byWindow = new LinkedHashMap<>();
        byWindow.put("byFirstObservation", stayByWindow);
        byWindow.put("failureByRisk", failureByRiskWindow.counts());
        outcomes.put("stayByWindow", byWindow);
        Map<String, Object> pairs = new LinkedHashMap<>();
        pairs.put("byFirstObservation", pairsByFirst.counts());
        pairs.put("byLastObservation", pairsByLast.counts());
        outcomes.put("firstTwoAttempts", pairs);
        return outcomes;
    }

    /** 판정이 아니라, 보드에 담긴 예보가 붙은 차량 한 대를 단위로 한 진단 집계. */
    Map<String, Object> boardVehicleOutcomes() {
        Map<String, Object> outcomes = new LinkedHashMap<>();
        outcomes.put("distinctVehicleStopPairs", boardVehicleStops.size());
        outcomes.putAll(boardVehicleOutcomes.counts());
        Map<String, Object> pairs = new LinkedHashMap<>();
        pairs.put("byFirstObservation", boardPairsByFirst.counts());
        pairs.put("byLastObservation", boardPairsByLast.counts());
        outcomes.put("nearestTwoVehicles", pairs);
        return outcomes;
    }

    Map<String, Object> results() {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("decisions", decisions);
        results.put("decisionsWhereRouteIdsDiffer", routeIdsDiffer);
        results.put("reasonCodes", reasonCodes);
        results.put("arrivalLists", arrivalLists);
        results.put("entriesPerList", entriesPerList);
        results.put("attemptsPerDecision", attemptsPerDecision);
        results.put("stayEvaluations", stayEvaluations);
        results.put("stayGaps", stayGaps.entrySet().stream().map(entry -> {
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("kind", entry.getKey().get(0));
            gap.put("list", entry.getKey().get(1));
            gap.put("reason", entry.getKey().get(2));
            gap.put("count", entry.getValue());
            return gap;
        }).toList());
        results.put("stayAssumptions", assumptions);
        results.put("stayFailureRisk", quantilesOf(stayRisks));
        Map<String, Object> lookups = new LinkedHashMap<>();
        lookups.put("allEntries", allEntries.counts());
        lookups.put("windowAttempts", windowAttempts.counts());
        results.put("seatForecastLookups", lookups);
        return results;
    }

    /** 판정 입력이 아닌 진단. 판정 시각에 보드를 읽을 수 있었는지와 그 정류장 방문에 담긴 차량 수. */
    Map<String, Object> boardAtDecision() {
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("status", boards);
        board.put("vehiclesAtStop", boardVehicles);
        board.put("vehiclesWithForecastAtStop", boardForecasts);
        return board;
    }

    static String listStateOf(StopList list) {
        return list instanceof Unavailable unavailable ? "UNAVAILABLE/" + unavailable.reason()
            : list.getClass().getSimpleName().toUpperCase();
    }

    static String evaluationOf(Evaluation evaluation) {
        return switch (evaluation) {
            case Evaluable ignored -> "EVALUABLE";
            case ConfirmedEmpty ignored -> "CONFIRMED_EMPTY";
            case Insufficient ignored -> "INSUFFICIENT";
        };
    }

    static String assumptionsOf(Evaluable evaluable) {
        return evaluable.assumptions().isEmpty() ? "NONE"
            : evaluable.assumptions().stream().map(Assumption::name).collect(Collectors.joining("+"));
    }

    static String gapOf(Gap gap) {
        return String.join("/", keyOf(gap));
    }

    static String lookupOf(EntryRecord entry) {
        return switch (entry.lookup()) {
            case Found found -> "FOUND/" + found.probabilityTarget();
            case NotFound notFound -> notFound.cause() + notFound.boardError().map(code -> "/" + code).orElse("")
                + entry.absence().map(absence -> "/" + absence).orElse("");
        };
    }

    /** 가장 가까운 순위 방식의 분위수. 값이 없으면 count만 0으로 돌려준다. */
    static Map<String, Object> quantilesOf(List<Double> values) {
        Map<String, Object> quantiles = new LinkedHashMap<>();
        quantiles.put("count", values.size());
        if (values.isEmpty()) {
            return quantiles;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        quantiles.put("min", sorted.get(0));
        for (double quantile : QUANTILES) {
            int rank = (int) Math.ceil(quantile * sorted.size());
            quantiles.put("p" + Math.round(quantile * 100), sorted.get(Math.max(rank, 1) - 1));
        }
        quantiles.put("max", sorted.get(sorted.size() - 1));
        return quantiles;
    }

    private static List<String> keyOf(Gap gap) {
        return List.of(gap.kind().name(), gap.list().map(Enum::name).orElse("-"),
            gap.reason().map(Enum::name).orElse("-"));
    }

    private static int compare(List<String> left, List<String> right) {
        return String.join("/", left).compareTo(String.join("/", right));
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static final class LookupCounts {
        private int entries;
        private final Map<String, Integer> found = new TreeMap<>();
        private final Map<String, Integer> notFound = new TreeMap<>();
        private final Map<String, Integer> boardReadFailures = new TreeMap<>();
        private final Map<String, Integer> vehicleNotOnBoard = new TreeMap<>();

        void add(EntryRecord entry) {
            entries++;
            Lookup lookup = entry.lookup();
            if (lookup instanceof Found value) {
                count(found, value.probabilityTarget());
            }
            if (lookup instanceof NotFound absent) {
                count(notFound, absent.cause().name());
                absent.boardError().ifPresent(code -> count(boardReadFailures, code.name()));
                entry.absence().ifPresent(absence -> count(vehicleNotOnBoard, absence.name()));
            }
        }

        Map<String, Object> counts() {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("entries", entries);
            counts.put("foundByProbabilityTarget", found);
            counts.put("notFoundByCause", notFound);
            counts.put("boardReadFailedByError", boardReadFailures);
            counts.put("vehicleNotOnBoardByAbsence", vehicleNotOnBoard);
            return counts;
        }
    }
}
