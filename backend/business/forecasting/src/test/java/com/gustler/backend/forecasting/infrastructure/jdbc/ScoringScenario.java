package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.api.forecast.CollectionResult;
import com.gustler.backend.forecasting.api.forecast.ForecastOutcome;
import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.application.forecast.ClosedForecast;
import com.gustler.backend.forecasting.application.forecast.PassLabel;
import com.gustler.backend.forecasting.application.forecast.PassLabelCalculator;
import com.gustler.backend.forecasting.application.forecast.ScoringReasons;
import com.gustler.backend.forecasting.application.forecast.ScoringResult;
import com.gustler.backend.forecasting.application.forecast.ScoringStage;
import com.gustler.backend.forecasting.application.forecast.UnresolvedForecast;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvents;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import com.gustler.backend.forecasting.support.BaselineReasons;
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
import java.util.function.Function;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정산 단계에 흘릴 관측 흐름과 예보를 만들고 정산 결과를 예측 단위로 모은다. 관측에서 {@link AlightingAwarePassEvents#resolve}로
 * 판정한 통과를 이벤트로 만든다.
 *
 * <p>{@link ScoringScenarioBaseline}의 기대값은 여기서 만드는 흐름에 묶여 있다. 관측, 예보를 만드는 순서와
 * {@link #random(long, Instant, int, int, double, boolean)}이 난수를 뽑는 순서를 바꾸면 기대값과 견줄 수 없다.
 */
final class ScoringScenario {

    static final long ROUTE = 7L;
    static final long VERSION = 70L;
    static final long DEPLOYMENT = 3L;
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Clock FIXED = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    ScoringScenario(List<Integer> realStops, final int turn, JsonNode context) {
        this.realStops = List.copyOf(realStops);
        this.turn = turn;
        this.context = context;
    }

    record Batch(
        long id,
        Instant at,
        Instant receivedAt,
        boolean hole,
        List<AlightingAwareObservation> rows,
        List<Forecast> forecasts
    ) {

    }

    record Forecast(long observationId, int capacity, int[] targets) {

    }

    record Key(long observationId, int target) {

    }

    /** 예측 하나의 정산 결과에서 견주는 열이다. 정답 값은 비트로 담는다. 근거 관측 번호, 정원, 계수 해시는 정산 완료에만 있다. */
    record Verdict(
        String state,
        String reason,
        Long labelBits,
        String hash,
        Integer capacity,
        Long arrival,
        Long last,
        Long closure,
        Long previousLast,
        Long previousClosure
    ) {

        static Verdict of(String state, String reason) {
            return new Verdict(
                    state,
                    reason,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    /** 정산 결과를 예측 단위로 모은 것. */
    record Scored(
        Map<Key, Verdict> verdicts,
        Map<Key, String> reasons,
        int labels,
        int unresolved
    ) {

    }

    long batch(Instant at, final boolean hole) {
        if (!batches.isEmpty() && at.isBefore(batches.getLast().at())) {
            throw new IllegalArgumentException("배치는 시각 순서로 더한다");
        }
        Batch batch = new Batch(
                nextBatch++,
                at,
                at.minusSeconds(2),
                hole,
                new ArrayList<>(),
                new ArrayList<>()
        );
        batches.add(batch);
        batchById.put(batch.id(), batch);
        return batch.id();
    }

    long row(
        final long batchId,
        String vehicle,
        final int passedStop,
        Integer seats
    ) {
        Batch batch = batchById.get(batchId);
        final boolean down = isDown(passedStop, 2, turn);
        AlightingAwareObservation row = new AlightingAwareObservation(
                nextObservation++,
                batchId,
                0,
                batch.at(),
                vehicle,
                passedStop,
                passedStop,
                2,
                seats,
                0,
                VERSION,
                batch.receivedAt(),
                down
        );
        batch.rows().add(row);
        rows.add(row);
        rowById.put(row.observationId(), row);
        return row.observationId();
    }

    void forecast(final long observationId, final int capacity, int... targets) {
        AlightingAwareObservation row = rowById.get(observationId);
        Forecast forecast = new Forecast(observationId, capacity, targets.clone());
        batchById.get(row.batchId()).forecasts().add(forecast);
        forecasts.add(forecast);
    }

    /** 관측 하나의 예측에 쓴 정원. */
    int forecastCapacity(final long observationId) {
        return forecasts.stream().filter(forecast -> forecast.observationId() == observationId).findFirst()
            .orElseThrow().capacity();
    }

    List<PassClosed> passes() {
        prepare();
        return passes;
    }

    /** 배치마다 수집 이벤트, 그 배치가 확정한 통과, 예보 이벤트 순서로 이벤트를 만든다. */
    List<Object> events() {
        prepare();
        Map<Long, List<PassClosed>> closedIn = new HashMap<>();
        for (PassClosed pass : passes) {
            closedIn.computeIfAbsent(pass.closureBatchId(), id -> new ArrayList<>()).add(pass);
        }
        List<Object> events = new ArrayList<>();
        for (Batch batch : batches) {
            final int saved = batch.rows().size();
            events.add(new ObservationBatchSeen(
                    batch.id(),
                    ROUTE,
                    VERSION,
                    batch.receivedAt(),
                    collectionResult(saved, batch.hole()),
                    storedRows(saved, batch.hole()),
                    saved
            ));
            events.addAll(closedIn.getOrDefault(batch.id(), List.of()));
            events.add(published(batch));
        }
        return events;
    }

    private static CollectionResult collectionResult(final int saved, final boolean hole) {
        if (saved == 0 && !hole) {
            return CollectionResult.EMPTY;
        }
        return CollectionResult.SUCCESS;
    }

    /** 관측 누락을 흉내 내려고 기록한 저장 행 수를 실제로 남은 행 수보다 하나 많게 둔다. */
    private static int storedRows(final int saved, final boolean hole) {
        if (hole) {
            return saved + 1;
        }
        return saved;
    }

    ScoringStage stage(final int limit) {
        return stage(limit, new AlightingAwarePassLabelCalculator());
    }

    ScoringStage stage(final int limit, PassLabelCalculator calculator) {
        return new ScoringStage(
                this::contextFor,
                version -> realStops,
                calculator,
                FIXED,
                limit
        );
    }

    private Optional<JsonNode> contextFor(final long deployment, final long version) {
        if (deployment == DEPLOYMENT && version == VERSION) {
            return Optional.of(context);
        }
        return Optional.empty();
    }

    static ScoringResult feed(ScoringStage stage, List<Object> events) {
        List<ScoringResult> results = new ArrayList<>();
        for (Object event : events) {
            results.add(accept(stage, event));
        }
        return ScoringResult.combine(results);
    }

    static List<ClosedForecast> matched(ScoringResult result) {
        return result.closed().stream()
            .filter(closed -> closed.label() != null)
            .toList();
    }

    static ScoringResult accept(ScoringStage stage, Object event) {
        return switch (event) {
            case ObservationBatchSeen seen -> stage.accept(seen);
            case PassClosed pass -> stage.accept(pass);
            case ForecastPublished published -> stage.accept(published);
            default -> throw new IllegalArgumentException("모르는 사건: " + event);
        };
    }

    /** 정산 결과를 예측 단위로 바꾼다. 저장소처럼 같은 키는 처음 것만 남긴다. */
    Scored scored(ScoringResult result) {
        Map<List<Object>, PassLabel> labels = firstLabelsByKey(result);
        Map<List<Object>, PassClosed> passByKey = passesByKey();
        Map<Key, Verdict> verdicts = new LinkedHashMap<>();
        Map<Key, String> reasons = new HashMap<>();
        Map<List<Object>, UnresolvedForecast> unresolved = firstUnresolvedByKey(result);
        for (UnresolvedForecast closed : unresolved.values()) {
            Key key = new Key(closed.vehicleObservationId(), closed.targetStopOrder());
            put(verdicts, key, Verdict.of(closed.state().name(),
                BaselineReasons.recordedName(closed.reason()).orElse("NONE:" + closed.reason())));
            reasons.put(key, closed.reason());
        }
        for (ClosedForecast forecast : matched(result)) {
            PassLabel matchedLabel = forecast.label();
            Key key = new Key(
                forecast.vehicleObservationId(),
                forecast.event().targetStopOrder()
            );
            PassLabel label = labels.get(List.of(
                    matchedLabel.sourceDate(),
                    matchedLabel.routeId(),
                    matchedLabel.tripStartObservationId(),
                    matchedLabel.stopOrder(),
                    matchedLabel.capacity(),
                    ScoringReasons.LABEL_VERSION,
                    matchedLabel.modelDeploymentId()
            ));
            PassClosed pass = passByKey.get(List.of(
                    matchedLabel.sourceDate(),
                    matchedLabel.routeId(),
                    matchedLabel.tripStartObservationId(),
                    matchedLabel.stopOrder()
            ));
            if (label.state() == PassLabel.State.SETTLED) {
                put(verdicts, key, new Verdict(
                        "SETTLED",
                        null,
                        Double.doubleToLongBits(label.labelValue()),
                        label.parameterHash(),
                        label.capacity(),
                        pass.arrivalObservationId(),
                        pass.lastObservationId(),
                        pass.closureObservationId(),
                        label.previousLastObservationId(),
                        label.previousClosureObservationId()
                ));
                continue;
            }
            put(verdicts, key, Verdict.of("UNSUPPORTED",
                BaselineReasons.recordedName(label.reason()).orElse("NONE:" + label.reason())));
            reasons.put(key, label.reason());
        }
        return new Scored(
                verdicts,
                reasons,
                labels.size(),
                unresolved.size()
        );
    }

    private static Map<List<Object>, PassLabel> firstLabelsByKey(ScoringResult result) {
        Map<List<Object>, PassLabel> labels = new LinkedHashMap<>();
        for (PassLabel label : result.labels()) {
            labels.putIfAbsent(List.of(
                    label.sourceDate(),
                    label.routeId(),
                    label.tripStartObservationId(),
                    label.stopOrder(),
                    label.capacity(),
                    label.labelVersion(),
                    label.modelDeploymentId()
            ), label);
        }
        return labels;
    }

    private Map<List<Object>, PassClosed> passesByKey() {
        Map<List<Object>, PassClosed> passByKey = new HashMap<>();
        for (PassClosed pass : passes()) {
            passByKey.put(List.of(
                    pass.sourceDate(),
                    pass.routeId(),
                    pass.tripStartObservationId(),
                    pass.stopOrder()
            ),
                pass);
        }
        return passByKey;
    }

    private static Map<List<Object>, UnresolvedForecast> firstUnresolvedByKey(ScoringResult result) {
        Map<List<Object>, UnresolvedForecast> unresolved = new LinkedHashMap<>();
        for (UnresolvedForecast closed : result.unresolved()) {
            unresolved.putIfAbsent(
                List.of(closed.sourceDate(), closed.vehicleObservationId(), closed.targetStopOrder()), closed);
        }
        return unresolved;
    }

    private static void put(
        Map<Key, Verdict> verdicts,
        Key key,
        Verdict verdict
    ) {
        Verdict before = verdicts.putIfAbsent(key, verdict);
        if (before != null && !before.equals(verdict)) {
            throw new AssertionError("한 예측에 결과가 둘이다: " + key + " " + before + " " + verdict);
        }
    }

    /** 모든 이벤트를 흘리고 마지막 배치 3시간 뒤에 마감한 정산 결과를 예측 단위로 모은다. 마감 뒤에도 기다리는 예측이 있으면 실패다. */
    Scored scoredToDeadline(final int limit) {
        ScoringStage stage = stage(limit);
        ScoringResult result = feed(stage, events())
            .plus(stage.expire(batches.getLast().at().plus(Duration.ofHours(3))));
        assertNothingWaits(stage);
        return scored(result);
    }

    private static void assertNothingWaits(ScoringStage stage) {
        if (stage.waitingCount() != 0) {
            throw new AssertionError("마감 뒤에도 기다리는 예측이 있다: " + stage.waitingCount());
        }
    }

    /** 예측 기준 관측의 차량이 그날 00시 전 3시간 안에도 관측되어, 자정을 넘겨 이어진 운행일 수 있는지 본다. */
    boolean continuesFromPreviousDay(Key key) {
        AlightingAwareObservation source = rowById.get(key.observationId());
        Instant dayStart = source.receivedAt().atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant();
        Instant earliest = source.receivedAt().minus(Duration.ofHours(3));
        return rows.stream().anyMatch(row -> isEarlierSameVehicle(
                row,
                source,
                dayStart,
                earliest
        ));
    }

    private static boolean isEarlierSameVehicle(
        AlightingAwareObservation row,
        AlightingAwareObservation source,
        Instant dayStart,
        Instant earliest
    ) {
        return row.vehicleId().equals(source.vehicleId())
            && row.receivedAt().isBefore(dayStart) && !row.receivedAt().isBefore(earliest);
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
        final long chain = chains.get(event.arrival().observationId());
        Instant reached = rows.stream()
            .filter(row -> isSeatObservationAtPass(row, event, chain))
            .map(AlightingAwareObservation::receivedAt).max(Instant::compareTo).orElse(null);
        LocalDate closedDay = event.confirmedAt().atZone(SEOUL).toLocalDate();
        return new PassClosed(
                closedDay,
                ROUTE,
                event.tripId(),
                event.stopOrder(),
                VERSION,
                event.vehicleId(),
                chain,
                event.departure().receivedAt().atZone(SEOUL).toLocalDate(),
                event.arrival().observationId(),
                event.arrival().seats(),
                event.arrival().receivedAt(),
                event.arrival().sourceReceivedAt(),
                event.firstDeparture().observationId(),
                event.firstDeparture().receivedAt(),
                event.departure().observationId(),
                event.departure().seats(),
                event.departure().receivedAt(),
                event.departure().sourceReceivedAt(),
                reached,
                event.confirmation().observationId(),
                event.confirmation().batchId(),
                event.confirmedAt()
        );
    }

    private boolean isSeatObservationAtPass(
        AlightingAwareObservation row,
        AlightingAwarePassEvent event,
        final long chain
    ) {
        return row.vehicleId().equals(event.vehicleId()) && chains.get(row.observationId()) == chain
            && row.passedStopOrder() == event.stopOrder() && row.seats() != null
            && !row.receivedAt().isAfter(event.confirmedAt());
    }

    private ForecastPublished published(Batch batch) {
        LocalDate day = batch.at().minusSeconds(5).atZone(SEOUL).toLocalDate();
        if (batch.forecasts().isEmpty()) {
            return new ForecastPublished(
                    day,
                    batch.id(),
                    ROUTE,
                    VERSION,
                    batch.receivedAt(),
                    batch.at(),
                    DEPLOYMENT,
                    ForecastOutcome.EMPTY,
                    List.of()
            );
        }
        List<ForecastPublished.VehicleForecast> vehicles = new ArrayList<>();
        int number = 0;
        for (Forecast forecast : batch.forecasts()) {
            AlightingAwareObservation row = rowById.get(forecast.observationId());
            final int size = forecast.targets().length;
            vehicles.add(new ForecastPublished.VehicleForecast(
                    row.observationId(),
                    number++,
                    row.vehicleId(),
                    row.passedStopOrder(),
                    trips.get(row.observationId()),
                    chains.get(row.observationId()),
                    row.down(),
                    forecast.capacity(),
                    false,
                    forecast.targets(),
                    new double[size],
                    new double[size]
            ));
        }
        return new ForecastPublished(
                day,
                batch.id(),
                ROUTE,
                VERSION,
                batch.receivedAt(),
                batch.at(),
                DEPLOYMENT,
                ForecastOutcome.PUBLISHED,
                vehicles
        );
    }

    static boolean isDown(final int stop, final int state, final int turn) {
        return stop > turn || (stop == turn && state == 2) || (stop == 1 && state != 2);
    }

    /**
     * 정산 근거를 만든다. 정류장 계수는 목표마다 출퇴근 두 칸이고 {@code missingEvening} 정류장은 퇴근 칸이 없다.
     * {@code extended}가 거짓이면 비첨두 계수를 빼고, {@code missingExtended} 칸은 비첨두 계수에서 뺀다.
     */
    static JsonNode context(
        List<Integer> realStops,
        final int turn,
        List<Integer> targets,
        final boolean extended,
        List<Integer> missingEvening,
        List<String> missingExtended
    ) {
        Function<Double, String> parameters = rate ->
            ("\"p\":0.12,\"lam\":[%s,%s,%s,%s,%s,%s,%s,%s],\"kappa\":1,\"r\":2")
            .formatted(
                    rate,
                    rate + .1,
                    rate + .2,
                    rate + .3,
                    rate + .4,
                    rate + .5,
                    rate + .6,
                    rate + .7
            );
        List<String> stops = new ArrayList<>();
        for (final int target : targets) {
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
                "\"realStopOrders\":" + realStops
        ));
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
    static ScoringScenario random(
        final long seed,
        Instant start,
        final int batchCount,
        final int vehicleCount,
        final double holeChance,
        final boolean extended
    ) {
        return random(
                seed,
                start,
                batchCount,
                vehicleCount,
                holeChance,
                extended,
                List.of("work-night")
        );
    }

    /** {@code missingExtended} 칸의 비첨두 계수를 뺀 근거로 {@link #random(long, Instant, int, int, double, boolean)}과 같이 만든다. */
    static ScoringScenario random(
        final long seed,
        Instant start,
        final int batchCount,
        final int vehicleCount,
        final double holeChance,
        final boolean extended,
        List<String> missingExtended
    ) {
        List<Integer> real = List.of(
                1,
                2,
                3,
                4,
                6,
                7,
                8,
                9
        );
        List<Integer> targets = List.of(
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                9
        );
        ScoringScenario scenario = new ScoringScenario(real, 20,
            context(
                    real,
                    20,
                    targets,
                    extended,
                    List.of(7),
                    missingExtended
            ));
        Random random = new Random(seed);
        List<SimulatedVehicle> vehicles = new ArrayList<>();
        for (int i = 0; i < vehicleCount; i++) {
            final int capacity = initialCapacity(i);
            vehicles.add(new SimulatedVehicle("bus-" + seed + "-" + i, capacity,
                start.plusSeconds(random.nextInt(600))));
        }
        Instant at = start;
        for (int index = 0; index < batchCount; index++) {
            at = at.plusSeconds(10 + random.nextInt(11));
            final long batch = scenario.batch(at, random.nextDouble() < holeChance);
            for (SimulatedVehicle vehicle : vehicles) {
                vehicle.step(
                        scenario,
                        batch,
                        at,
                        random
                );
            }
        }
        return scenario;
    }

    /** 첫 차량은 정원이 바뀌지 않는 75석이고, 나머지는 40석과 45석이 번갈아 나온다. */
    private static int initialCapacity(final int index) {
        if (index == 0) {
            return 75;
        }
        if (index % 2 == 0) {
            return 40;
        }
        return 45;
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

        private SimulatedVehicle(String id, final int capacity, Instant nextStart) {
            this.id = id;
            this.capacity = capacity;
            this.nextStart = nextStart;
        }

        /** 난수를 뽑는 순서와 횟수가 시나리오를 정하므로, 조건마다 뽑던 자리를 그대로 지킨다. */
        private void step(
            ScoringScenario scenario,
            final long batch,
            Instant at,
            Random random
        ) {
            if (!active) {
                if (at.isBefore(nextStart)) {
                    return;
                }
                depart(random);
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
                capacity = otherCapacity(capacity);
            }
            Integer reported = reportedSeats(random);
            final long observation = scenario.row(
                    batch,
                    id,
                    stop,
                    reported
            );
            if (reported != null && stop < 9) {
                int[] wanted = IntStream.rangeClosed(stop + 1, Math.min(9, stop + 4)).toArray();
                scenario.forecast(observation, capacity, wanted);
            }
            if (stop == 9 && ++dwell > 2) {
                active = false;
                nextStart = at.plusSeconds(restSeconds(random));
            }
        }

        private void depart(Random random) {
            active = true;
            stop = 1;
            seats = 25 + random.nextInt(21);
            dwell = 0;
        }

        private static int otherCapacity(final int capacity) {
            if (capacity == 40) {
                return 45;
            }
            return 40;
        }

        /** 난수 하나를 뽑아 가끔 좌석을 알 수 없는 관측을 만든다. */
        private Integer reportedSeats(Random random) {
            if (random.nextDouble() < 0.03) {
                return null;
            }
            return seats;
        }

        /** 짧은 휴식과 긴 휴식 가운데 하나를 고른 뒤, 고른 쪽의 범위에서만 난수를 하나 더 뽑는다. */
        private static int restSeconds(Random random) {
            if (random.nextDouble() < 0.5) {
                return 60 + random.nextInt(600);
            }
            return 1900 + random.nextInt(1200);
        }
    }
}
