package com.gustler.backend.forecasting.application.pipeline;

import static com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareIdentifiers.canonical;

import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.pipeline.SourceDates;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 통과가 닫히는 순간 그 통과의 정답을 한 번 계산하고, 기다리던 예측을 한꺼번에 닫는 채점 단계다.
 *
 * <p>한 스레드에서 사건을 차례로 받는다. DB를 읽지 않고 메모리 상태만 쓰며 동시 호출을 지원하지 않는다. 노선 안의 사건은 배치의
 * 확정 시각 순서로 들어와야 하고, 같은 배치에서는 {@link ObservationBatchSeen}, {@link PassClosed}들, {@link ForecastPublished}
 * 순서다. 노선의 시각은 받은 사건의 시각(확정 시각, 수집 사건은 수신 시각) 가운데 가장 늦은 값이다. 수집 사건은 저장되지 않으므로
 * 되감기는 그 자리에 예보 실행 줄에 적어 둔 수집 사실을 {@link #restore}로 넣는다. 예보 단계가 되감으며 되찾은 통과는 노선 시각보다
 * 이른 닫힌 시각으로 늦게 올 수 있다. 그런 통과도 닫힌 시각으로 직전 통과와 짝을 찾으며 노선 시각은 되돌리지 않는다.
 *
 * <p>판정은 옛 확정 판 정산과 같다.
 * <ul>
 *   <li>짝: 같은 노선·같은 연결 시작 관측·같은 목표 정류장의 통과이고, 통과가 예측 기준 시각(착수 시각) + 2시간 안에 닫혔으며,
 *       목표에 정확히 도달한 좌석 있는 관측 가운데 가장 늦은 것이 기준 시각 + 10초보다 뒤다.</li>
 *   <li>직전 통과: 같은 노선·같은 정류장·같은 출발 날짜에서 닫힌 시각이 엄격히 더 이른 통과 가운데 가장 늦게 닫힌 것이다.
 *       같은 시각에 닫힌 것이 여럿이면 마지막 관측 번호가 가장 작은 것이다. 차량은 따지지 않는다. 같은 시각에 닫힌 통과는
 *       서로 직전이 되지 않는다.</li>
 *   <li>정답 값은 {@link PassLabelCalculator}가 옛 계산을 그대로 불러 낸다. 근거는 모델 배포의 정산 근거에 예측의 정원과
 *       목표 정류장 계수를 합친 것이다. 한 통과에 짝이 된 예측은 (모델 배포, 정원)마다 정답 하나를 받는다. 한 연결은 한 차량·한
 *       노선 판본의 관측이므로 한 통과에 짝이 된 예측은 노선 판본이 모두 같다.</li>
 *   <li>예측 기준 시각의 한국 날짜 00시부터 판정 시각까지 관측 상한을 넘었거나 수집 구멍이 있으면 지원 안 함으로 닫는다.
 *       판정 시각은 짝이 된 통과가 닫힌 시각, 마감이면 기준 시각 + 2시간이다. 옛 정산은 실제 평가 시각까지 보았다.</li>
 *   <li>통과가 생길 수 없는 목표(실정류장 목록의 첫·마지막이거나 실정류장이 아닌 목표)는 기다리지 않고 바로 놓침으로 닫는다.</li>
 *   <li>한 연결·정류장의 통과는 하나뿐이다. 그래서 통과가 닫힌 순간 그 통과를 기다리던 예측은 모두 결론이 난다. 짝이 되지
 *       않은 예측은 그 자리에서 {@link ScoringReasons#PASS_CONDITION_UNMET}(또는 그 시각까지의 수집 구멍·관측 상한 사유)으로
 *       닫고, 통과가 이미 닫힌 뒤에 온 예측도 받는 순간 같은 사유로 닫는다. 옛 정산은 이런 예측을 마감 때 닫았으므로 마감 전에 생긴
 *       수집 구멍이 옛 결과에만 보일 수 있다.</li>
 *   <li>노선 시각이 기준 시각 + 2시간을 넘으면 기다리던 예측을 놓침으로 닫는다. 사유는 연결이 바뀜, 통과 없음 순으로 고른다.</li>
 * </ul>
 * 따라서 닫힌 통과가 있는 (연결, 정류장)의 예측은 모두 미해결로 닫혔거나 자기 (모델 배포, 정원)의 정답에 짝지어졌다. 두 결과는
 * 같은 {@link ScoringResult}에 함께 담긴다. 예측별 결과 뷰({@code forecast_outcome})가 이 성질에 기댄다.
 * 새 사유를 옛 사유로 바꾸는 대응표는 {@link ScoringReasons}에 있다.
 *
 * <p>같은 사건을 두 번 넣어도 결과 집합이 같다. 같은 배치의 {@link ObservationBatchSeen}과 {@link ForecastPublished}는
 * 3시간 안에 다시 오면 건너뛰고, 다시 온 {@link PassClosed}는 기다리는 예측이 이미 빠져 있어 새 결과를 내지 않는다.
 * {@link PassLabelCalculator}가 {@link IllegalArgumentException} 밖의 예외를 던지면 그대로 전파하며, 그때 노선 상태는 일부만
 * 바뀌었을 수 있으므로 호출하는 쪽은 {@link #discard(long)}로 노선 상태를 버리고 되감아야 한다.
 */
public final class ScoringStage {
    /** 예측 기준 시각부터 통과를 기다리는 시간. */
    public static final Duration MATCH_WINDOW = Duration.ofHours(2);
    /** 도달 관측이 예측 기준 시각보다 늦어야 하는 최소 시간. */
    public static final Duration MINIMUM_LEAD = Duration.ofSeconds(10);
    /** 노선에 배치가 이만큼 없으면 {@link #expire(Instant)}가 벽시계로 마감한다. */
    public static final Duration IDLE_EXPIRY = Duration.ofMinutes(130);
    /** 옛 정산의 하루 관측 상한 기본값({@code forecast.alighting.causal-evaluation.maximum-observation-rows}). */
    public static final int DEFAULT_OBSERVATION_LIMIT = 150_000;

    private static final String CAPACITY_POLICY = "vehicle-capacity-snapshot-v1";
    private static final Duration RETENTION = Duration.ofMinutes(130);
    private static final Duration BATCH_MEMORY = Duration.ofHours(3);
    private static final Duration HOLE_MEMORY = Duration.ofDays(2);
    private static final Duration PRUNE_INTERVAL = Duration.ofMinutes(1);
    private static final int DAY_WINDOW_EXTRA_HOURS = 2;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SettlementContextProvider contexts;
    private final RouteStructureProvider structures;
    private final PassLabelCalculator calculator;
    private final Clock clock;
    private final int observationLimit;
    private final Map<Long, RouteState> routes = new HashMap<>();
    private final Map<EvidenceKey, JsonNode> evidence = new HashMap<>();
    private final Map<EvidenceKey, Instant> evidenceMissingAt = new HashMap<>();
    private final Map<Long, Set<Integer>> passStops = new HashMap<>();

    /**
     * 옛 정산의 하루 관측 상한 기본값으로 채점 단계를 만든다.
     *
     * @param contexts 모델 배포의 정산 근거
     * @param structures 노선 판본의 실정류장
     * @param calculator 정답 계산
     * @param clock 정답 계산 시각, 미해결 닫은 시각, 정산 근거가 없음을 확인한 시각의 출처
     */
    public ScoringStage(
        SettlementContextProvider contexts,
        RouteStructureProvider structures,
        PassLabelCalculator calculator,
        Clock clock
    ) {
        this(contexts, structures, calculator, clock, DEFAULT_OBSERVATION_LIMIT);
    }

    /**
     * 채점 단계를 만든다.
     *
     * @param contexts 모델 배포의 정산 근거
     * @param structures 노선 판본의 실정류장
     * @param calculator 정답 계산
     * @param clock 정답 계산 시각, 미해결 닫은 시각, 정산 근거가 없음을 확인한 시각의 출처
     * @param observationLimit 하루(00시부터 다음 날 02시까지) 관측 상한. 1 이상이다
     */
    public ScoringStage(
        SettlementContextProvider contexts,
        RouteStructureProvider structures,
        PassLabelCalculator calculator,
        Clock clock,
        final int observationLimit
    ) {
        this.contexts = Objects.requireNonNull(contexts, "정산 근거 제공자가 필요하다");
        this.structures = Objects.requireNonNull(structures, "노선 구조 제공자가 필요하다");
        this.calculator = Objects.requireNonNull(calculator, "정답 계산이 필요하다");
        this.clock = Objects.requireNonNull(clock, "시계가 필요하다");
        if (observationLimit < 1) {
            throw new IllegalArgumentException("관측 상한은 1 이상이어야 한다: " + observationLimit);
        }
        this.observationLimit = observationLimit;
    }

    /**
     * 수집 사건을 받는다. 노선 시각을 수신 시각(없으면 예약 시각)까지 전진시키고, 수집 구멍과 관측 줄 수를 그 배치의 확정 시각에
     * 기록한다. 확정 시각은 같은 배치의 다음 사건에서 알며, 다음 배치의 수집 사건이 먼저 오면 수신 시각으로 기록한다.
     *
     * @param seen 수집 사건
     * @return 마감으로 닫힌 예측
     */
    public ScoringResult accept(ObservationBatchSeen seen) {
        RouteState state = route(seen.routeId());
        if (state.seenBatches.containsKey(seen.observationBatchId())) {
            return ScoringResult.empty();
        }
        return see(state, new PendingBatch(seen.observationBatchId(), seen.incomplete(), seen.countedRows(),
            seenAt(seen)));
    }

    private static Instant seenAt(ObservationBatchSeen seen) {
        if (seen.receivedAt() == null) {
            return seen.scheduledAt();
        }
        return seen.receivedAt();
    }

    /**
     * 되감기에서 저장된 배치의 수집 사실을 되살린다. 그 배치의 수집 사건을 받은 뒤 같은 배치의 예보 사건이 수집 구멍과 관측 줄 수를
     * 착수 시각에 기록하던 것과 결과가 같다. 늦게 착수한 배치는 기록하지 않는다. 같은 노선의 배치는 착수 시각 순서로, 같은 배치의
     * 통과·예보 사건보다 먼저 넣어야 한다. 이미 본 배치면 건너뛰므로 되감은 뒤에 같은 배치의 수집 사건이 와도 두 번 세지 않는다.
     *
     * @param batch 저장된 수집 사실
     * @return 마감으로 닫힌 예측
     */
    public ScoringResult restore(RecordedBatch batch) {
        RouteState state = route(batch.routeId());
        if (state.seenBatches.containsKey(batch.observationBatchId())) {
            return ScoringResult.empty();
        }
        ScoringResult result = see(state, new PendingBatch(batch.observationBatchId(), batch.incomplete(),
            batch.countedRows(), batch.receivedAt()));
        resolveBatch(state, batch.observationBatchId(), batch.confirmedAt(), batch.late());
        return result;
    }

    private ScoringResult see(RouteState state, PendingBatch seen) {
        state.seenBatches.put(seen.batchId(), seen.fallbackAt());
        for (PendingBatch pending : List.copyOf(state.pendingBatches.values())) {
            state.pendingBatches.remove(pending.batchId());
            recordBatch(state, pending, pending.fallbackAt());
        }
        Instant now = clock.instant();
        ScoringResult result = advance(state, seen.fallbackAt(), now);
        if (seen.incomplete() || seen.rows() > 0) {
            state.pendingBatches.put(seen.batchId(), seen);
        }
        return result;
    }

    /**
     * 통과 사건을 받는다. 직전 통과를 찾고, 이 통과를 기다리던 예측 가운데 짝이 되는 것을 골라 (모델 배포, 정원)마다 정답을 낸 뒤
     * 대기에서 뺀다. 짝이 되지 않는 예측도 대기에서 빼고 {@link ScoringReasons#PASS_CONDITION_UNMET}으로 바로 닫는다.
     *
     * @param pass 통과 사건
     * @return 새 정답, 짝이 된 예측, 지원 안 함으로 닫힌 예측, 마감으로 닫힌 예측
     */
    public ScoringResult accept(PassClosed pass) {
        RouteState state = route(pass.routeId());
        Instant now = clock.instant();
        resolveBatch(state, pass.closureBatchId(), pass.closedAt(), false);
        ScoringResult result = advance(state, pass.closedAt(), now);
        seeChain(state, pass.vehicleId(), pass.chainStartObservationId(), pass.closedAt());
        TreeMap<Instant, PassClosed> history = state.recentPasses
            .computeIfAbsent(new StopDay(pass.stopOrder(), pass.departureDate()), key -> new TreeMap<>());
        Map.Entry<Instant, PassClosed> lower = history.lowerEntry(pass.closedAt());
        PassClosed previous = previousPass(lower);
        WaitKey key = new WaitKey(pass.chainStartObservationId(), pass.stopOrder());
        state.closedChainStops.merge(key, pass.closedAt(), ScoringStage::later);
        List<Waiting> matched = new ArrayList<>();
        List<Waiting> unmet = new ArrayList<>();
        Map<ForecastKey, Waiting> candidates = state.waitingByPass.get(key);
        if (candidates != null) {
            for (Waiting waiting : candidates.values()) {
                if (matches(pass, waiting)) {
                    matched.add(waiting);
                    continue;
                }
                unmet.add(waiting);
            }
        }
        result = result.plus(score(state, pass, previous, matched, now)).plus(unmet(state, pass, unmet, now));
        history.merge(pass.closedAt(), pass, ScoringStage::smallerLastObservation);
        return result;
    }

    private static PassClosed previousPass(Map.Entry<Instant, PassClosed> lower) {
        if (lower == null) {
            return null;
        }
        return lower.getValue();
    }

    private static Instant later(Instant left, Instant right) {
        if (left.isAfter(right)) {
            return left;
        }
        return right;
    }

    private static PassClosed smallerLastObservation(PassClosed kept, PassClosed added) {
        if (added.lastObservationId() < kept.lastObservationId()) {
            return added;
        }
        return kept;
    }

    /**
     * 예보 사건을 받는다. 버스별 목표 정류장마다 예측을 대기에 넣는다. 정산 근거가 없거나 근거의 입력 판·정원 정책이 맞지 않거나
     * 그날 관측 상한·수집 구멍이 이미 있으면 지원 안 함으로, 통과가 생길 수 없는 목표면 놓침으로, 같은 연결의 그 정류장 통과가
     * 이미 닫혔으면 조건 불충족 놓침으로 바로 닫는다.
     *
     * @param published 예보 사건
     * @return 바로 닫힌 예측과 마감으로 닫힌 예측
     */
    public ScoringResult accept(ForecastPublished published) {
        RouteState state = route(published.routeId());
        if (state.publishedBatches.containsKey(published.observationBatchId())) {
            return ScoringResult.empty();
        }
        state.publishedBatches.put(published.observationBatchId(), published.confirmedAt());
        Instant now = clock.instant();
        resolveBatch(state, published.observationBatchId(), published.confirmedAt(),
            published.outcome() == ForecastOutcome.LATE);
        ScoringResult result = advance(state, published.confirmedAt(), now);
        if (!published.outcome().carriesForecasts()) {
            return result;
        }
        JsonNode context = evidence(published.modelDeploymentId(), published.routeVersionId(),
            published.confirmedAt());
        Set<Integer> inner = passStops(published.routeVersionId());
        List<UnresolvedForecast> closed = new ArrayList<>();
        for (ForecastPublished.VehicleForecast vehicle : published.vehicles()) {
            seeChain(state, vehicle.vehicleId(), vehicle.chainStartObservationId(), published.confirmedAt());
            for (final int target : vehicle.targetStopOrders()) {
                ForecastKey key = new ForecastKey(vehicle.vehicleObservationId(), target);
                if (state.waiting.containsKey(key)) {
                    continue;
                }
                Waiting waiting = new Waiting(key, published.routeId(), published.sourceDate(), vehicle.vehicleId(),
                    vehicle.chainStartObservationId(), published.confirmedAt(), vehicle.capacity(),
                    published.modelDeploymentId(), published.routeVersionId());
                UnresolvedForecast immediate = closeImmediately(state, waiting, context, inner, now);
                if (immediate != null) {
                    closed.add(immediate);
                    continue;
                }
                state.add(waiting);
            }
        }
        return result.plus(new ScoringResult(List.of(), closed, List.of()));
    }

    private UnresolvedForecast closeImmediately(
        RouteState state,
        Waiting waiting,
        JsonNode context,
        Set<Integer> inner,
        Instant now
    ) {
        String unsupported = precondition(state, waiting, context);
        if (unsupported != null) {
            return unresolved(waiting, UnresolvedForecast.State.UNSUPPORTED, unsupported, now);
        }
        if (!inner.contains(waiting.key.targetStopOrder())) {
            return unresolved(waiting, UnresolvedForecast.State.LOST, ScoringReasons.TARGET_WITHOUT_PASS, now);
        }
        if (state.closedChainStops.containsKey(waiting.passKey())) {
            return unresolved(waiting, UnresolvedForecast.State.LOST, ScoringReasons.PASS_CONDITION_UNMET, now);
        }
        return null;
    }

    /**
     * 노선에 {@link #IDLE_EXPIRY}보다 오래 사건이 없으면 벽시계 기준으로 마감이 지난 예측을 닫는다. 노선 시각은 바꾸지 않는다.
     *
     * @param now 벽시계 시각
     * @return 마감으로 닫힌 예측
     */
    public ScoringResult expire(Instant now) {
        Objects.requireNonNull(now, "시각이 필요하다");
        Instant closedAt = clock.instant();
        ScoringResult result = ScoringResult.empty();
        for (Map.Entry<Long, RouteState> entry : routes.entrySet()) {
            RouteState state = entry.getValue();
            if (state.clock != null && state.clock.plus(IDLE_EXPIRY).isBefore(now)) {
                result = result.plus(expireThrough(state, now, closedAt));
            }
        }
        return result;
    }

    /**
     * 노선의 메모리 상태를 모두 버린다. 저장에 실패했거나 사건 처리 중 예외가 난 노선을 되감기 전에 부른다.
     *
     * @param routeId 노선
     */
    public void discard(final long routeId) {
        routes.remove(routeId);
    }

    /** 모든 노선에서 통과를 기다리는 예측의 수. */
    public int waitingCount() {
        return routes.values().stream().mapToInt(state -> state.waiting.size()).sum();
    }

    /**
     * 노선에서 통과를 기다리는 예측의 수.
     *
     * @param routeId 노선
     * @return 기다리는 예측의 수
     */
    public int waitingCount(final long routeId) {
        RouteState state = routes.get(routeId);
        if (state == null) {
            return 0;
        }
        return state.waiting.size();
    }

    private RouteState route(final long routeId) {
        return routes.computeIfAbsent(routeId, id -> new RouteState());
    }

    private static boolean matches(PassClosed pass, Waiting waiting) {
        return pass.vehicleId().equals(waiting.vehicleId)
            && !pass.closedAt().isAfter(waiting.deadline())
            && pass.reachedKnownSeatAt() != null
            && pass.reachedKnownSeatAt().isAfter(waiting.basis.plus(MINIMUM_LEAD));
    }

    private ScoringResult score(
        RouteState state,
        PassClosed pass,
        PassClosed previous,
        List<Waiting> matched,
        Instant now
    ) {
        if (matched.isEmpty()) {
            return ScoringResult.empty();
        }
        matched.sort(Waiting.ORDER);
        List<UnresolvedForecast> unresolved = new ArrayList<>();
        Map<LabelGroup, List<Waiting>> groups = new LinkedHashMap<>();
        for (Waiting waiting : matched) {
            state.remove(waiting);
            String taint = taint(state, waiting, pass.closedAt());
            if (taint != null) {
                unresolved.add(unresolved(waiting, UnresolvedForecast.State.UNSUPPORTED, taint, now));
                continue;
            }
            groups.computeIfAbsent(new LabelGroup(waiting.modelDeploymentId, waiting.capacity),
                group -> new ArrayList<>()).add(waiting);
        }
        List<PassLabel> labels = new ArrayList<>();
        List<ScoredForecast> scored = new ArrayList<>();
        for (Map.Entry<LabelGroup, List<Waiting>> group : groups.entrySet()) {
            LabelGroup labelGroup = group.getKey();
            Waiting first = group.getValue().getFirst();
            JsonNode context = evidence(labelGroup.modelDeploymentId(), first.routeVersionId, first.basis);
            JsonNode stops = stopEvidence(context, pass.stopOrder());
            if (stops == null) {
                group.getValue().forEach(waiting -> unresolved.add(unresolved(waiting,
                    UnresolvedForecast.State.UNSUPPORTED, ScoringReasons.SETTLEMENT_EVIDENCE_MISSING, now)));
                continue;
            }
            PassLabelCalculator.PassLabelCalculation calculation =
                calculate(pass, previous, snapshot(context, stops, labelGroup.capacity()));
            labels.add(label(pass, previous, labelGroup, calculation, now));
            for (Waiting waiting : group.getValue()) {
                scored.add(new ScoredForecast(waiting.sourceDate, waiting.key.vehicleObservationId(),
                    waiting.key.targetStopOrder(), pass.routeId(), pass.sourceDate(), pass.tripStartObservationId(),
                    pass.stopOrder(), labelGroup.capacity(), ScoringReasons.LABEL_VERSION,
                    labelGroup.modelDeploymentId()));
            }
        }
        return new ScoringResult(labels, unresolved, scored);
    }

    private static JsonNode stopEvidence(JsonNode context, final int stopOrder) {
        if (context == null) {
            return null;
        }
        return context.path("stops").get(String.valueOf(stopOrder));
    }

    private static ScoringResult unmet(
        RouteState state,
        PassClosed pass,
        List<Waiting> unmet,
        Instant now
    ) {
        if (unmet.isEmpty()) {
            return ScoringResult.empty();
        }
        unmet.sort(Waiting.ORDER);
        List<UnresolvedForecast> closed = new ArrayList<>();
        for (Waiting waiting : unmet) {
            state.remove(waiting);
            String taint = taint(state, waiting, pass.closedAt());
            if (taint != null) {
                closed.add(unresolved(waiting, UnresolvedForecast.State.UNSUPPORTED, taint, now));
                continue;
            }
            closed.add(unresolved(waiting, UnresolvedForecast.State.LOST, ScoringReasons.PASS_CONDITION_UNMET, now));
        }
        return new ScoringResult(List.of(), closed, List.of());
    }

    private PassLabelCalculator.PassLabelCalculation calculate(
        PassClosed pass,
        PassClosed previous,
        JsonNode snapshot
    ) {
        try {
            return Objects.requireNonNull(calculator.calculate(pass, previous, snapshot), "정답 계산 결과가 필요하다");
        } catch (IllegalArgumentException failure) {
            return PassLabelCalculator.PassLabelCalculation.unsupported(ScoringReasons.SETTLEMENT_CALCULATION_FAILED);
        }
    }

    private static PassLabel label(
        PassClosed pass,
        PassClosed previous,
        LabelGroup group,
        PassLabelCalculator.PassLabelCalculation calculation,
        Instant now
    ) {
        return new PassLabel(pass.sourceDate(), pass.routeId(), pass.tripStartObservationId(), pass.stopOrder(),
            group.capacity(), ScoringReasons.LABEL_VERSION, group.modelDeploymentId(),
            labelState(calculation), calculation.reason(),
            calculation.value(), previousLastObservationId(previous),
            previousClosureObservationId(previous), calculation.parameterHash(), now);
    }

    private static PassLabel.State labelState(PassLabelCalculator.PassLabelCalculation calculation) {
        if (calculation.settled()) {
            return PassLabel.State.SETTLED;
        }
        return PassLabel.State.UNSUPPORTED;
    }

    private static Long previousLastObservationId(PassClosed previous) {
        if (previous == null) {
            return null;
        }
        return previous.lastObservationId();
    }

    private static Long previousClosureObservationId(PassClosed previous) {
        if (previous == null) {
            return null;
        }
        return previous.closureObservationId();
    }

    private static JsonNode snapshot(JsonNode context, JsonNode stops, final int capacity) {
        ObjectNode snapshot = NODES.objectNode();
        for (Map.Entry<String, JsonNode> field : context.path("shared").properties()) {
            snapshot.set(field.getKey(), field.getValue());
        }
        snapshot.put("capacity", capacity);
        snapshot.putObject("alighting").set("stops", stops);
        return snapshot;
    }

    private String precondition(RouteState state, Waiting waiting, JsonNode context) {
        if (stopEvidence(context, waiting.key.targetStopOrder()) == null) {
            return ScoringReasons.SETTLEMENT_EVIDENCE_MISSING;
        }
        JsonNode shared = context.path("shared");
        String inputProfile = canonical(shared.path("inputProfile").asString());
        if (!AlightingAwareRouteStatistics.CONFIRMED_PROFILE.equals(inputProfile)) {
            return ScoringReasons.INPUT_PROFILE_MISMATCH;
        }
        if (!CAPACITY_POLICY.equals(shared.path("capacityPolicy").asString())) {
            return ScoringReasons.CAPACITY_POLICY_MISMATCH;
        }
        return taint(state, waiting, waiting.basis);
    }

    private static String taint(RouteState state, Waiting waiting, Instant through) {
        LocalDate day = SourceDates.of(waiting.basis);
        Instant overflow = state.overflowAt.get(day);
        if (overflow != null && !overflow.isAfter(through)) {
            return ScoringReasons.OBSERVATION_INPUT_LIMIT;
        }
        Instant hole = state.holes.ceiling(day.atStartOfDay(SourceDates.KOREA).toInstant());
        if (hole != null && !hole.isAfter(through)) {
            return ScoringReasons.SOURCE_HISTORY_INCOMPLETE;
        }
        return null;
    }

    private ScoringResult advance(RouteState state, Instant at, Instant now) {
        if (state.clock != null && !at.isAfter(state.clock)) {
            return ScoringResult.empty();
        }
        state.clock = at;
        ScoringResult result = expireThrough(state, at, now);
        if (state.prunedAt == null || !state.prunedAt.plus(PRUNE_INTERVAL).isAfter(at)) {
            state.prune(at);
        }
        return result;
    }

    private ScoringResult expireThrough(RouteState state, Instant through, Instant now) {
        List<UnresolvedForecast> closed = new ArrayList<>();
        while (!state.byDeadline.isEmpty() && state.byDeadline.firstKey().deadline().isBefore(through)) {
            Waiting waiting = state.byDeadline.firstEntry().getValue();
            state.remove(waiting);
            closed.add(lost(state, waiting, now));
        }
        if (closed.isEmpty()) {
            return ScoringResult.empty();
        }
        return new ScoringResult(List.of(), closed, List.of());
    }

    private static UnresolvedForecast lost(RouteState state, Waiting waiting, Instant now) {
        String taint = taint(state, waiting, waiting.deadline());
        if (taint != null) {
            return unresolved(waiting, UnresolvedForecast.State.UNSUPPORTED, taint, now);
        }
        String reason = ScoringReasons.PASS_NOT_CLOSED;
        ChainSeen chain = state.chains.get(waiting.vehicleId);
        if (chain != null && chain.chainStartObservationId() != waiting.chainStartObservationId) {
            reason = ScoringReasons.CHAIN_BROKEN;
        }
        return unresolved(waiting, UnresolvedForecast.State.LOST, reason, now);
    }

    private static UnresolvedForecast unresolved(
        Waiting waiting,
        UnresolvedForecast.State state,
        String reason,
        Instant now
    ) {
        return new UnresolvedForecast(waiting.sourceDate, waiting.key.vehicleObservationId(),
            waiting.key.targetStopOrder(), waiting.routeId, state, reason, now);
    }

    private static void seeChain(
        RouteState state,
        String vehicleId,
        final long chainStartObservationId,
        Instant at
    ) {
        state.chains.merge(vehicleId, new ChainSeen(chainStartObservationId, at), ScoringStage::laterChain);
    }

    private static ChainSeen laterChain(ChainSeen kept, ChainSeen seen) {
        if (seen.at().isBefore(kept.at())) {
            return kept;
        }
        return seen;
    }

    private void resolveBatch(
        RouteState state,
        final long batchId,
        Instant at,
        final boolean late
    ) {
        PendingBatch pending = state.pendingBatches.remove(batchId);
        if (pending != null && !late) {
            recordBatch(state, pending, at);
        }
    }

    private void recordBatch(RouteState state, PendingBatch pending, Instant at) {
        if (pending.incomplete()) {
            state.holes.add(at);
        }
        if (pending.rows() <= 0) {
            return;
        }
        LocalDate day = SourceDates.of(at);
        count(state, day, pending.rows(), at);
        if (!at.isAfter(day.atStartOfDay(SourceDates.KOREA).plusHours(DAY_WINDOW_EXTRA_HOURS).toInstant())) {
            count(state, day.minusDays(1), pending.rows(), at);
        }
    }

    private void count(
        RouteState state,
        LocalDate day,
        final int rows,
        Instant at
    ) {
        final long total = state.rowCounts.merge(day, (long) rows, Long::sum);
        if (total > observationLimit) {
            state.overflowAt.merge(day, at, ScoringStage::earlier);
        }
    }

    private static Instant earlier(Instant kept, Instant added) {
        if (added.isBefore(kept)) {
            return added;
        }
        return kept;
    }

    /**
     * 예측의 정산 근거를 찾는다. 찾은 근거는 계속 기억한다. 근거가 없으면 그것을 확인한 시각을 기억해 두고, 그 시각보다 뒤에 착수한
     * 예측을 받을 때만 다시 묻는다. 본선은 배치를 착수하기 전에 근거를 적으므로, 확인한 뒤에 착수한 예측에는 그사이 근거가 생겼을 수
     * 있고 확인하기 전에 착수한 예측(되감기로 다시 받는 지난 예측)에는 그럴 수 없다. 그래서 근거가 없는 노선도 배치마다 한 번,
     * 되감기에서는 한 번만 묻는다. 일정 시간 동안 "없음"으로 기억하면 그사이 근거가 적혀도 그 뒤의 예측이 근거 없음으로 닫히고, 먼저
     * 쓴 미해결 줄은 다시 고칠 수 없다.
     */
    private JsonNode evidence(Long modelDeploymentId, final long routeVersionId, Instant confirmedAt) {
        if (modelDeploymentId == null) {
            return null;
        }
        EvidenceKey key = new EvidenceKey(modelDeploymentId, routeVersionId);
        JsonNode cached = evidence.get(key);
        if (cached != null) {
            return cached;
        }
        Instant missingAt = evidenceMissingAt.get(key);
        if (missingAt != null && !confirmedAt.isAfter(missingAt)) {
            return null;
        }
        Instant asked = clock.instant();
        JsonNode found = contexts.find(modelDeploymentId, routeVersionId).orElse(null);
        if (found == null) {
            evidenceMissingAt.put(key, asked);
            return null;
        }
        evidence.put(key, found);
        evidenceMissingAt.remove(key);
        return found;
    }

    private Set<Integer> passStops(final long routeVersionId) {
        return passStops.computeIfAbsent(routeVersionId, id -> {
            List<Integer> stops = structures.realStopOrders(id).stream().distinct().sorted().toList();
            if (stops.size() < 3) {
                return Set.of();
            }
            return Set.copyOf(stops.subList(1, stops.size() - 1));
        });
    }

    private record EvidenceKey(long modelDeploymentId, long routeVersionId) {
    }

    private record LabelGroup(long modelDeploymentId, int capacity) {
    }

    private record ForecastKey(long vehicleObservationId, int targetStopOrder) {
    }

    private record WaitKey(long chainStartObservationId, int stopOrder) {
    }

    private record StopDay(int stopOrder, LocalDate departureDate) {
    }

    private record ChainSeen(long chainStartObservationId, Instant at) {
    }

    private record PendingBatch(long batchId, boolean incomplete, int rows, Instant fallbackAt) {
    }

    private record DeadlineKey(Instant deadline, long vehicleObservationId, int targetStopOrder)
        implements Comparable<DeadlineKey> {
        private static final Comparator<DeadlineKey> ORDER = Comparator.comparing(DeadlineKey::deadline)
            .thenComparingLong(DeadlineKey::vehicleObservationId)
            .thenComparingInt(DeadlineKey::targetStopOrder);

        @Override
        public int compareTo(DeadlineKey other) {
            return ORDER.compare(this, other);
        }
    }

    private static final class Waiting {
        private static final Comparator<Waiting> ORDER = Comparator.comparing((Waiting waiting) -> waiting.basis)
            .thenComparingLong(waiting -> waiting.key.vehicleObservationId())
            .thenComparingInt(waiting -> waiting.key.targetStopOrder());

        private final ForecastKey key;
        private final long routeId;
        private final LocalDate sourceDate;
        private final String vehicleId;
        private final long chainStartObservationId;
        private final Instant basis;
        private final int capacity;
        private final long modelDeploymentId;
        private final long routeVersionId;

        private Waiting(
            ForecastKey key,
            final long routeId,
            LocalDate sourceDate,
            String vehicleId,
            final long chainStartObservationId,
            Instant basis,
            final int capacity,
            final long modelDeploymentId,
            final long routeVersionId
        ) {
            this.key = key;
            this.routeId = routeId;
            this.sourceDate = sourceDate;
            this.vehicleId = vehicleId;
            this.chainStartObservationId = chainStartObservationId;
            this.basis = basis;
            this.capacity = capacity;
            this.modelDeploymentId = modelDeploymentId;
            this.routeVersionId = routeVersionId;
        }

        private Instant deadline() {
            return basis.plus(MATCH_WINDOW);
        }

        private WaitKey passKey() {
            return new WaitKey(chainStartObservationId, key.targetStopOrder());
        }

        private DeadlineKey deadlineKey() {
            return new DeadlineKey(deadline(), key.vehicleObservationId(), key.targetStopOrder());
        }
    }

    private static final class RouteState {
        private final Map<ForecastKey, Waiting> waiting = new HashMap<>();
        private final Map<WaitKey, Map<ForecastKey, Waiting>> waitingByPass = new HashMap<>();
        private final TreeMap<DeadlineKey, Waiting> byDeadline = new TreeMap<>();
        private final Map<StopDay, TreeMap<Instant, PassClosed>> recentPasses = new HashMap<>();
        private final Map<WaitKey, Instant> closedChainStops = new HashMap<>();
        private final Map<String, ChainSeen> chains = new HashMap<>();
        private final Map<Long, Instant> seenBatches = new HashMap<>();
        private final Map<Long, Instant> publishedBatches = new HashMap<>();
        private final Map<Long, PendingBatch> pendingBatches = new LinkedHashMap<>();
        private final TreeSet<Instant> holes = new TreeSet<>();
        private final Map<LocalDate, Long> rowCounts = new HashMap<>();
        private final Map<LocalDate, Instant> overflowAt = new HashMap<>();
        private Instant clock;
        private Instant prunedAt;

        private void add(Waiting entry) {
            waiting.put(entry.key, entry);
            waitingByPass.computeIfAbsent(entry.passKey(), key -> new LinkedHashMap<>()).put(entry.key, entry);
            byDeadline.put(entry.deadlineKey(), entry);
        }

        private void remove(Waiting entry) {
            waiting.remove(entry.key);
            byDeadline.remove(entry.deadlineKey());
            Map<ForecastKey, Waiting> sameKey = waitingByPass.get(entry.passKey());
            if (sameKey != null) {
                sameKey.remove(entry.key);
                if (sameKey.isEmpty()) {
                    waitingByPass.remove(entry.passKey());
                }
            }
        }

        private void prune(Instant at) {
            prunedAt = at;
            LocalDate yesterday = SourceDates.of(at).minusDays(1);
            recentPasses.keySet().removeIf(key -> key.departureDate().isBefore(yesterday));
            closedChainStops.values().removeIf(closed -> closed.plus(RETENTION).isBefore(at));
            chains.values().removeIf(chain -> chain.at().plus(RETENTION).isBefore(at));
            seenBatches.values().removeIf(seen -> seen.plus(BATCH_MEMORY).isBefore(at));
            publishedBatches.values().removeIf(seen -> seen.plus(BATCH_MEMORY).isBefore(at));
            holes.headSet(at.minus(HOLE_MEMORY)).clear();
            rowCounts.keySet().removeIf(day -> day.isBefore(yesterday));
            overflowAt.keySet().removeIf(day -> day.isBefore(yesterday));
        }
    }

}
