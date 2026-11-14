package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.api.pipeline.CollectionResult;
import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished.VehicleForecast;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.application.pipeline.PassLabel;
import com.gustler.backend.forecasting.application.pipeline.PassLabelCalculator;
import com.gustler.backend.forecasting.application.pipeline.ScoredForecast;
import com.gustler.backend.forecasting.application.pipeline.ScoringReasons;
import com.gustler.backend.forecasting.application.pipeline.ScoringResult;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.application.pipeline.UnresolvedForecast;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvents;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 같은 관측 흐름을 옛 정산 기준({@link ConfirmedAlightingAwareEvaluationOracle})과 새 채점 단계에 함께 흘려 예측별 결과를 맞춘다.
 *
 * <p>옛 결과는 정산이 배치마다 한 번씩 평가하는 이상적인 일정으로 정한다. 예측 기준 시각부터 2시간 안의 평가 시각을 차례로 보며
 * 처음 나온 결과가 그 예측의 결과이고, 끝까지 없으면 놓침이다. 평가는 예측 기준 시각의 한국 날짜 00시부터 평가 시각까지의 관측만
 * 쓴다(옛 투영과 같은 날짜별 범위). 새 채점에는 같은 관측을 날짜 구분 없이 {@link AlightingAwarePassEvents#resolve}로 판정한
 * 통과를 사건으로 만들어 흘린다.
 */
final class LegacyScoringScenario {
    static final long ROUTE = 7L;
    static final long VERSION = 70L;
    static final long DEPLOYMENT = 3L;
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Clock FIXED = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LEGACY_LOST = "NO_CLOSED_IDENTITY_SAFE_PASS";
    private static final List<String> TAINTS = List.of("SOURCE_HISTORY_INCOMPLETE", "OBSERVATION_INPUT_LIMIT");
    /** 새 채점이 마감을 기다리지 않고 바로 닫는 사유. 옛 정산은 마감까지 보므로 그 사이에 생긴 수집 구멍·상한이 옛 결과에만 있다. */
    private static final List<String> IMMEDIATE = List.of(ScoringReasons.TARGET_WITHOUT_PASS,
        ScoringReasons.PASS_CONDITION_UNMET);

    private final List<Integer> realStops;
    private final int turn;
    private final JsonNode context;
    private final List<Batch> batches = new ArrayList<>();
    private final Map<Long, Batch> batchById = new HashMap<>();
    private final List<AlightingAwareObservation> rows = new ArrayList<>();
    private final Map<Long, AlightingAwareObservation> rowById = new HashMap<>();
    private final List<Forecast> forecasts = new ArrayList<>();
    private long nextBatch = 1;
    private long nextObservation = 1000;
    private List<PassClosed> passes;
    private Map<Long, Long> chains;
    private Map<Long, Long> trips;

    LegacyScoringScenario(List<Integer> realStops, int turn, JsonNode context) {
        this.realStops = List.copyOf(realStops);
        this.turn = turn;
        this.context = context;
    }

    record Batch(long id, Instant at, Instant receivedAt, boolean hole, List<AlightingAwareObservation> rows,
                 List<Forecast> forecasts) {
    }

    record Forecast(long observationId, int capacity, int[] targets) {
    }

    record Key(long observationId, int target) {
    }

    /** 대조 열이다. 정답 값은 비트로 비교한다. 근거 관측 번호·정원·계수 해시는 채점됨에만 있다. */
    record Verdict(String state, String reason, Long labelBits, String hash, Integer capacity, Long pre, Long post,
                   Long closure, Long previousPost, Long previousClosure) {
        static Verdict of(String state, String reason) {
            return new Verdict(state, reason, null, null, null, null, null, null, null, null);
        }
    }

    record Difference(Key key, Verdict legacy, Verdict scored, String newReason) {
    }

    /** 대조 결과. 차이는 설명된 종류별로 센다. */
    record Comparison(int forecasts, Map<String, Integer> legacyOutcomes, int equal, int midnightScope,
                      int taintTiming, List<Difference> unexplained) {
    }

    /** 새 채점이 낸 결과를 예측 단위로 모은 것. */
    record Scored(Map<Key, Verdict> verdicts, Map<Key, String> reasons, int labels, int unresolved) {
    }

    long batch(Instant at, boolean hole) {
        if (!batches.isEmpty() && at.isBefore(batches.getLast().at())) {
            throw new IllegalArgumentException("배치는 시각 순서로 더한다");
        }
        Batch batch = new Batch(nextBatch++, at, at.minusSeconds(2), hole, new ArrayList<>(), new ArrayList<>());
        batches.add(batch);
        batchById.put(batch.id(), batch);
        return batch.id();
    }

    long row(long batchId, String vehicle, int passedStop, Integer seats) {
        Batch batch = batchById.get(batchId);
        boolean down = isDown(passedStop, 2, turn);
        AlightingAwareObservation row = new AlightingAwareObservation(nextObservation++, batchId, 0, batch.at(), vehicle,
            passedStop, passedStop, 2, seats, 0, VERSION, batch.receivedAt(), down);
        batch.rows().add(row);
        rows.add(row);
        rowById.put(row.observationId(), row);
        return row.observationId();
    }

    void forecast(long observationId, int capacity, int... targets) {
        AlightingAwareObservation row = rowById.get(observationId);
        Forecast forecast = new Forecast(observationId, capacity, targets.clone());
        batchById.get(row.batchId()).forecasts().add(forecast);
        forecasts.add(forecast);
    }

    /** 관측 하나의 예측에 쓴 정원. */
    int forecastCapacity(long observationId) {
        return forecasts.stream().filter(forecast -> forecast.observationId() == observationId).findFirst()
            .orElseThrow().capacity();
    }

    List<PassClosed> passes() {
        prepare();
        return passes;
    }

    /** 배치마다 수집 사건, 그 배치가 닫은 통과, 예보 사건 순서로 사건을 만든다. */
    List<Object> events() {
        prepare();
        Map<Long, List<PassClosed>> closedIn = new HashMap<>();
        for (PassClosed pass : passes) {
            closedIn.computeIfAbsent(pass.closureBatchId(), id -> new ArrayList<>()).add(pass);
        }
        List<Object> events = new ArrayList<>();
        for (Batch batch : batches) {
            int saved = batch.rows().size();
            events.add(new ObservationBatchSeen(batch.id(), ROUTE, VERSION, batch.at().minusSeconds(5), batch.receivedAt(),
                saved == 0 && !batch.hole() ? CollectionResult.EMPTY : CollectionResult.SUCCESS,
                saved + (batch.hole() ? 1 : 0), saved));
            events.addAll(closedIn.getOrDefault(batch.id(), List.of()));
            events.add(published(batch));
        }
        return events;
    }

    ScoringStage stage(int limit) {
        return stage(limit, new ConfirmedPassLabelCalculator());
    }

    ScoringStage stage(int limit, PassLabelCalculator calculator) {
        return new ScoringStage((deployment, version) -> deployment == DEPLOYMENT && version == VERSION
            ? Optional.of(context) : Optional.empty(), version -> realStops, calculator, FIXED, limit);
    }

    static ScoringResult feed(ScoringStage stage, List<Object> events) {
        ScoringResult result = ScoringResult.empty();
        for (Object event : events) {
            result = result.plus(accept(stage, event));
        }
        return result;
    }

    static ScoringResult accept(ScoringStage stage, Object event) {
        return switch (event) {
            case ObservationBatchSeen seen -> stage.accept(seen);
            case PassClosed pass -> stage.accept(pass);
            case ForecastPublished published -> stage.accept(published);
            default -> throw new IllegalArgumentException("모르는 사건: " + event);
        };
    }

    /** 새 채점 결과를 예측 단위로 바꾼다. 저장소처럼 같은 키는 처음 것만 남긴다. */
    Scored scored(ScoringResult result) {
        Map<List<Object>, PassLabel> labels = new LinkedHashMap<>();
        for (PassLabel label : result.labels()) {
            labels.putIfAbsent(List.of(label.sourceDate(), label.routeId(), label.tripStartObservationId(),
                label.stopOrder(), label.capacity(), label.labelVersion(), label.modelDeploymentId()), label);
        }
        Map<List<Object>, PassClosed> passByKey = new HashMap<>();
        for (PassClosed pass : passes()) {
            passByKey.put(List.of(pass.sourceDate(), pass.routeId(), pass.tripStartObservationId(), pass.stopOrder()), pass);
        }
        Map<Key, Verdict> verdicts = new LinkedHashMap<>();
        Map<Key, String> reasons = new HashMap<>();
        Map<List<Object>, UnresolvedForecast> unresolved = new LinkedHashMap<>();
        for (UnresolvedForecast closed : result.unresolved()) {
            unresolved.putIfAbsent(List.of(closed.sourceDate(), closed.vehicleObservationId(), closed.targetStopOrder()),
                closed);
        }
        for (UnresolvedForecast closed : unresolved.values()) {
            Key key = new Key(closed.vehicleObservationId(), closed.targetStopOrder());
            put(verdicts, key, Verdict.of(closed.state().name(),
                ScoringReasons.legacyReason(closed.reason()).orElse("NONE:" + closed.reason())));
            reasons.put(key, closed.reason());
        }
        for (ScoredForecast forecast : result.scored()) {
            Key key = new Key(forecast.vehicleObservationId(), forecast.targetStopOrder());
            PassLabel label = labels.get(List.of(forecast.passSourceDate(), forecast.routeId(),
                forecast.tripStartObservationId(), forecast.stopOrder(), forecast.capacity(), forecast.labelVersion(),
                forecast.modelDeploymentId()));
            PassClosed pass = passByKey.get(List.of(forecast.passSourceDate(), forecast.routeId(),
                forecast.tripStartObservationId(), forecast.stopOrder()));
            if (label.state() == PassLabel.State.SETTLED) {
                put(verdicts, key, new Verdict("SETTLED", null, Double.doubleToLongBits(label.labelValue()),
                    label.parameterHash(), label.capacity(), pass.arrivalObservationId(), pass.lastObservationId(),
                    pass.closureObservationId(), label.previousLastObservationId(),
                    label.previousClosureObservationId()));
            } else {
                put(verdicts, key, Verdict.of("UNSUPPORTED",
                    ScoringReasons.legacyReason(label.reason()).orElse("NONE:" + label.reason())));
                reasons.put(key, label.reason());
            }
        }
        return new Scored(verdicts, reasons, labels.size(), unresolved.size());
    }

    private static void put(Map<Key, Verdict> verdicts, Key key, Verdict verdict) {
        Verdict before = verdicts.putIfAbsent(key, verdict);
        if (before != null && !before.equals(verdict)) {
            throw new AssertionError("한 예측에 결과가 둘이다: " + key + " " + before + " " + verdict);
        }
    }

    /** 옛 정산 기준으로 예측마다 결과를 낸다. */
    Map<Key, Verdict> legacy(int limit) {
        return legacy(limit, false);
    }

    private Map<Key, Verdict> legacy(int limit, boolean continuous) {
        prepare();
        Map<List<Object>, ConfirmedAlightingAwareEvaluationOracle> oracles = new HashMap<>();
        Map<Key, Verdict> verdicts = new LinkedHashMap<>();
        TreeSet<Instant> times = new TreeSet<>(batches.stream().map(Batch::at).toList());
        for (Forecast forecast : forecasts) {
            AlightingAwareObservation source = rowById.get(forecast.observationId());
            for (int target : forecast.targets()) {
                verdicts.put(new Key(source.observationId(), target),
                    legacyVerdict(source, target, forecast.capacity(), times, oracles, limit, continuous));
            }
        }
        return verdicts;
    }

    private Verdict legacyVerdict(AlightingAwareObservation source, int target, int capacity, TreeSet<Instant> times,
        Map<List<Object>, ConfirmedAlightingAwareEvaluationOracle> oracles, int limit, boolean continuous) {
        LocalDate day = source.receivedAt().atZone(SEOUL).toLocalDate();
        Instant deadline = source.receivedAt().plus(ScoringStage.MATCH_WINDOW);
        JsonNode snapshot = snapshot(target, capacity);
        for (Instant through : candidates(source, target, times, deadline, limit)) {
            ConfirmedAlightingAwareEvaluationOracle oracle = oracles.computeIfAbsent(List.of(day, through, continuous),
                key -> oracle(day, through, limit, continuous));
            JdbcAlightingAwareForecastEvaluation.Outcome outcome = oracle.evaluate(source.observationId(), target,
                snapshot);
            if (outcome != null) {
                return verdict(outcome);
            }
        }
        return Verdict.of("LOST", LEGACY_LOST);
    }

    /**
     * 옛 평가 결과가 바뀔 수 있는 평가 시각만 고른다. 결과는 같은 차량이 목표를 지나 통과가 닫히는 배치, 수집 구멍·관측 상한이
     * 생기는 배치에서만 비어 있지 않게 바뀐다. 첫 평가 시각과 마지막 평가 시각도 넣는다.
     */
    private List<Instant> candidates(AlightingAwareObservation source, int target, TreeSet<Instant> times,
        Instant deadline, int limit) {
        TreeSet<Instant> chosen = new TreeSet<>();
        Instant first = times.ceiling(source.receivedAt());
        Instant last = times.floor(deadline);
        if (first == null || last == null || first.isAfter(last)) {
            return List.of();
        }
        chosen.add(first);
        chosen.add(last);
        LocalDate day = source.receivedAt().atZone(SEOUL).toLocalDate();
        Instant dayStart = day.atStartOfDay(SEOUL).toInstant();
        long counted = 0;
        for (Batch batch : batches) {
            if (batch.at().isBefore(dayStart) || batch.at().isAfter(last)) {
                continue;
            }
            counted += batch.rows().size();
            boolean inWindow = !batch.at().isBefore(first);
            if (!inWindow) {
                continue;
            }
            if (batch.hole() || counted > limit) {
                chosen.add(batch.at());
            }
            for (AlightingAwareObservation row : batch.rows()) {
                if (row.vehicleId().equals(source.vehicleId()) && row.passedStopOrder() > target) {
                    chosen.add(batch.at());
                }
            }
        }
        return List.copyOf(chosen);
    }

    private ConfirmedAlightingAwareEvaluationOracle oracle(LocalDate day, Instant through, int limit,
        boolean continuous) {
        Instant start = continuous ? Instant.MIN : day.atStartOfDay(SEOUL).toInstant();
        Instant dayStart = day.atStartOfDay(SEOUL).toInstant();
        List<AlightingAwareObservation> window = rows.stream()
            .filter(row -> !row.receivedAt().isBefore(start) && !row.receivedAt().isAfter(through))
            .sorted(Comparator.comparing(AlightingAwareObservation::receivedAt)
                .thenComparingLong(AlightingAwareObservation::batchId)
                .thenComparingLong(AlightingAwareObservation::observationId))
            .limit(continuous ? Long.MAX_VALUE : limit + 1L)
            .sorted(Comparator.comparing(AlightingAwareObservation::vehicleId)
                .thenComparing(AlightingAwareObservation::receivedAt)
                .thenComparingLong(AlightingAwareObservation::batchId)
                .thenComparingLong(AlightingAwareObservation::observationId))
            .toList();
        Instant firstHole = batches.stream()
            .filter(batch -> batch.hole() && !batch.at().isBefore(dayStart) && !batch.at().isAfter(through))
            .map(Batch::at).min(Instant::compareTo).orElse(null);
        return new ConfirmedAlightingAwareEvaluationOracle(new ConfirmedAlightingAwareEvaluationOracle.History(window,
            through, continuous ? Integer.MAX_VALUE - 1 : limit, firstHole), day, through);
    }

    private static Verdict verdict(JdbcAlightingAwareForecastEvaluation.Outcome outcome) {
        if (!outcome.state().equals("SETTLED")) {
            return Verdict.of(outcome.state(), outcome.reason());
        }
        return new Verdict("SETTLED", null, Double.doubleToLongBits(outcome.label()), outcome.parameterHash(),
            outcome.capacity(), outcome.preObservationId(), outcome.postObservationId(), outcome.closureObservationId(),
            outcome.previousPostObservationId(), outcome.previousClosureObservationId());
    }

    /** 옛 결과와 새 결과를 예측 단위로 맞추고 차이를 설명된 종류로 나눈다. */
    Comparison compare(int limit) {
        Map<Key, Verdict> legacy = legacy(limit);
        ScoringStage stage = stage(limit);
        ScoringResult result = feed(stage, events()).plus(stage.expire(batches.getLast().at().plus(Duration.ofHours(3))));
        assertNothingWaits(stage);
        return compare(legacy, scored(result), limit);
    }

    private static void assertNothingWaits(ScoringStage stage) {
        if (stage.waitingCount() != 0) {
            throw new AssertionError("마감 뒤에도 기다리는 예측이 있다: " + stage.waitingCount());
        }
    }

    Comparison compare(Map<Key, Verdict> legacy, Scored scored, int limit) {
        Map<Key, Verdict> continuous = null;
        Map<String, Integer> outcomes = new TreeMap<>();
        int equal = 0;
        int midnight = 0;
        int taint = 0;
        List<Difference> unexplained = new ArrayList<>();
        for (Map.Entry<Key, Verdict> entry : legacy.entrySet()) {
            Key key = entry.getKey();
            Verdict expected = entry.getValue();
            outcomes.merge(expected.state() + ":" + expected.reason(), 1, Integer::sum);
            Verdict actual = scored.verdicts().get(key);
            if (expected.equals(actual)) {
                equal++;
                continue;
            }
            if (continuous == null) {
                continuous = legacy(limit, true);
            }
            String reason = scored.reasons().get(key);
            if (actual != null && actual.equals(continuous.get(key)) && !sameDayHistory(key)) {
                midnight++;
            } else if (IMMEDIATE.contains(reason) && TAINTS.contains(expected.reason())) {
                taint++;
            } else {
                unexplained.add(new Difference(key, expected, actual, reason));
            }
        }
        if (scored.verdicts().size() != legacy.size()) {
            for (Key key : scored.verdicts().keySet()) {
                if (!legacy.containsKey(key)) {
                    unexplained.add(new Difference(key, null, scored.verdicts().get(key), scored.reasons().get(key)));
                }
            }
        }
        return new Comparison(legacy.size(), outcomes, equal, midnight, taint, unexplained);
    }

    /** 예측 기준 관측의 차량이 그날 00시 이전부터 같은 연결로 이어졌는지(자정 범위 차이가 생길 수 있는지) 본다. */
    private boolean sameDayHistory(Key key) {
        AlightingAwareObservation source = rowById.get(key.observationId());
        Instant dayStart = source.receivedAt().atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant();
        Instant earliest = source.receivedAt().minus(Duration.ofHours(3));
        return rows.stream().noneMatch(row -> row.vehicleId().equals(source.vehicleId())
            && row.receivedAt().isBefore(dayStart) && !row.receivedAt().isBefore(earliest));
    }

    private void prepare() {
        if (passes != null) {
            return;
        }
        List<AlightingAwareObservation> ordered = rows.stream()
            .sorted(Comparator.comparing(AlightingAwareObservation::vehicleId)
                .thenComparing(AlightingAwareObservation::receivedAt)
                .thenComparingLong(AlightingAwareObservation::batchId)
                .thenComparingLong(AlightingAwareObservation::observationId))
            .toList();
        chains = new HashMap<>();
        trips = new HashMap<>();
        AlightingAwareObservation previous = null;
        long chain = 0;
        long trip = 0;
        for (AlightingAwareObservation row : ordered) {
            if (row.startsConfirmedChainAfter(previous)) {
                chain = row.observationId();
            }
            if (row.startsConfirmedTripAfter(previous)) {
                trip = row.observationId();
            }
            chains.put(row.observationId(), chain);
            trips.put(row.observationId(), trip);
            previous = row;
        }
        passes = AlightingAwarePassEvents.resolve(rows, realStops).stream().map(this::pass).toList();
    }

    private PassClosed pass(AlightingAwarePassEvent event) {
        long chain = chains.get(event.arrival().observationId());
        Instant reached = rows.stream()
            .filter(row -> row.vehicleId().equals(event.vehicleId()) && chains.get(row.observationId()) == chain
                && row.passedStopOrder() == event.stopOrder() && row.seats() != null
                && !row.receivedAt().isAfter(event.confirmedAt()))
            .map(AlightingAwareObservation::receivedAt).max(Instant::compareTo).orElse(null);
        LocalDate closedDay = event.confirmedAt().atZone(SEOUL).toLocalDate();
        return new PassClosed(closedDay, ROUTE, event.tripId(), event.stopOrder(), VERSION, event.vehicleId(), chain,
            event.departure().receivedAt().atZone(SEOUL).toLocalDate(), event.arrival().observationId(),
            event.arrival().seats(), event.arrival().receivedAt(), event.arrival().sourceReceivedAt(),
            event.firstDeparture().observationId(), event.firstDeparture().receivedAt(),
            event.departure().observationId(), event.departure().seats(), event.departure().receivedAt(),
            event.departure().sourceReceivedAt(), reached, event.confirmation().observationId(),
            event.confirmation().batchId(), event.confirmedAt());
    }

    private ForecastPublished published(Batch batch) {
        LocalDate day = batch.at().minusSeconds(5).atZone(SEOUL).toLocalDate();
        if (batch.forecasts().isEmpty()) {
            return new ForecastPublished(day, batch.id(), ROUTE, VERSION, batch.receivedAt(), batch.at(), DEPLOYMENT,
                ForecastOutcome.EMPTY, List.of());
        }
        List<VehicleForecast> vehicles = new ArrayList<>();
        int number = 0;
        for (Forecast forecast : batch.forecasts()) {
            AlightingAwareObservation row = rowById.get(forecast.observationId());
            int size = forecast.targets().length;
            vehicles.add(new VehicleForecast(row.observationId(), number++, row.vehicleId(), row.passedStopOrder(),
                trips.get(row.observationId()), chains.get(row.observationId()), row.down(), forecast.capacity(), false,
                forecast.targets(), new double[size], new double[size]));
        }
        return new ForecastPublished(day, batch.id(), ROUTE, VERSION, batch.receivedAt(), batch.at(), DEPLOYMENT,
            ForecastOutcome.PUBLISHED, vehicles);
    }

    /** 새 채점 단계와 같은 방법으로 옛 정산이 예보 행에서 펼치는 근거를 만든다. */
    JsonNode snapshot(int target, int capacity) {
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> field : context.path("shared").properties()) {
            snapshot.set(field.getKey(), field.getValue());
        }
        snapshot.put("capacity", capacity);
        snapshot.putObject("alighting").set("stops", context.path("stops").get(String.valueOf(target)));
        return snapshot;
    }

    static boolean isDown(int stop, int state, int turn) {
        return stop > turn || (stop == turn && state == 2) || (stop == 1 && state != 2);
    }

    /**
     * 정산 근거를 만든다. 정류장 계수는 목표마다 출퇴근 두 칸이고 {@code missingEvening} 정류장은 퇴근 칸이 없다.
     * {@code extended}가 거짓이면 비첨두 계수를 빼고, {@code missingExtended} 칸은 비첨두 계수에서 뺀다.
     */
    static JsonNode context(List<Integer> realStops, int turn, List<Integer> targets, boolean extended,
        List<Integer> missingEvening, List<String> missingExtended) {
        Function<Double, String> parameters = rate -> ("\"p\":0.12,\"lam\":[%s,%s,%s,%s,%s,%s,%s,%s],\"kappa\":1,\"r\":2")
            .formatted(rate, rate + .1, rate + .2, rate + .3, rate + .4, rate + .5, rate + .6, rate + .7);
        List<String> stops = new ArrayList<>();
        for (int target : targets) {
            List<String> entries = new ArrayList<>();
            for (String band : List.of("morning", "evening")) {
                if (band.equals("evening") && missingEvening.contains(target)) {
                    continue;
                }
                entries.add("{\"stop\":%d,\"band\":\"%s\",%s}".formatted(target, band,
                    parameters.apply(1 + target / 10.0)));
            }
            stops.add("\"%d\":[%s]".formatted(target, String.join(",", entries)));
        }
        List<String> shared = new ArrayList<>(List.of(
            "\"capacityPolicy\":\"vehicle-capacity-snapshot-v1\"",
            "\"inputProfile\":\"" + AlightingAwareRouteStatistics.CONFIRMED_PROFILE + "\"",
            "\"turn\":" + turn,
            "\"realStopOrders\":" + realStops));
        if (extended) {
            List<String> cells = AlightingTimeCell.extendedKeys().stream()
                .filter(key -> !missingExtended.contains(key))
                .map(key -> "\"%s\":{%s}".formatted(key, parameters.apply(2.0)))
                .toList();
            shared.add("\"extendedSettlement\":{" + String.join(",", cells) + "}");
        }
        return JSON.readTree("{\"shared\":{%s},\"stops\":{%s}}".formatted(String.join(",", shared),
            String.join(",", stops)));
    }

    /**
     * 고정 시드로 여러 차량의 운행을 만든다. 정류장은 1~9이고 5는 실정류장이 아니다. 차량은 대략 45초마다 한 정류장을 지나며,
     * 가끔 관측이 빠지거나 90초 넘게 끊기고, 좌석이 비거나 정원이 바뀐다. 끝 정류장에 닿으면 몇 분에서 수십 분 쉰 뒤 다시 출발한다.
     */
    static LegacyScoringScenario random(long seed, Instant start, int batchCount, int vehicleCount,
        double holeChance, boolean extended) {
        return random(seed, start, batchCount, vehicleCount, holeChance, extended, List.of("work-night"));
    }

    /** {@code missingExtended} 칸의 비첨두 계수를 뺀 근거로 {@link #random(long, Instant, int, int, double, boolean)}과 같이 만든다. */
    static LegacyScoringScenario random(long seed, Instant start, int batchCount, int vehicleCount,
        double holeChance, boolean extended, List<String> missingExtended) {
        List<Integer> real = List.of(1, 2, 3, 4, 6, 7, 8, 9);
        List<Integer> targets = List.of(2, 3, 4, 5, 6, 7, 8, 9);
        LegacyScoringScenario scenario = new LegacyScoringScenario(real, 20,
            context(real, 20, targets, extended, List.of(7), missingExtended));
        Random random = new Random(seed);
        List<SimulatedVehicle> vehicles = new ArrayList<>();
        for (int i = 0; i < vehicleCount; i++) {
            int capacity = i == 0 ? 75 : (i % 2 == 0 ? 40 : 45);
            vehicles.add(new SimulatedVehicle("bus-" + seed + "-" + i, capacity,
                start.plusSeconds(random.nextInt(600))));
        }
        Instant at = start;
        for (int index = 0; index < batchCount; index++) {
            at = at.plusSeconds(10 + random.nextInt(11));
            long batch = scenario.batch(at, random.nextDouble() < holeChance);
            for (SimulatedVehicle vehicle : vehicles) {
                vehicle.step(scenario, batch, at, random);
            }
        }
        return scenario;
    }

    private static final class SimulatedVehicle {
        private final String id;
        private int capacity;
        private Instant nextStart;
        private Instant silentUntil;
        private boolean active;
        private int stop;
        private int seats;
        private int dwell;

        private SimulatedVehicle(String id, int capacity, Instant nextStart) {
            this.id = id;
            this.capacity = capacity;
            this.nextStart = nextStart;
        }

        private void step(LegacyScoringScenario scenario, long batch, Instant at, Random random) {
            if (!active) {
                if (at.isBefore(nextStart)) {
                    return;
                }
                active = true;
                stop = 1;
                seats = 25 + random.nextInt(21);
                dwell = 0;
            }
            if (silentUntil != null && at.isBefore(silentUntil)) {
                return;
            }
            if (random.nextDouble() < 0.006) {
                silentUntil = at.plusSeconds(95 + random.nextInt(120));
                return;
            }
            if (random.nextDouble() < 0.04) {
                return;
            }
            if (stop < 9 && random.nextDouble() < 0.35) {
                stop++;
                seats = Math.max(0, Math.min(45, seats + random.nextInt(9) - 4));
            }
            if (capacity != 75 && random.nextDouble() < 0.01) {
                capacity = capacity == 40 ? 45 : 40;
            }
            Integer reported = random.nextDouble() < 0.03 ? null : seats;
            long observation = scenario.row(batch, id, stop, reported);
            if (reported != null && stop < 9) {
                int[] wanted = java.util.stream.IntStream.rangeClosed(stop + 1, Math.min(9, stop + 4)).toArray();
                scenario.forecast(observation, capacity, wanted);
            }
            if (stop == 9 && ++dwell > 2) {
                active = false;
                nextStart = at.plusSeconds(random.nextDouble() < 0.5 ? 60 + random.nextInt(600)
                    : 1900 + random.nextInt(1200));
            }
        }
    }
}
