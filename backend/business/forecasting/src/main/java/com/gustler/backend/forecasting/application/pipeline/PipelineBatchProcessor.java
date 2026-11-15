package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.api.pipeline.CollectionResult;
import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePredictionAssembler;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.pipeline.SourceDates;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 관측 배치 하나를 예보하고 저장하는 단계다.
 *
 * <p>한 번의 처리는 다음 순서다. 착수 시각은 부르는 쪽이 {@link ObservationBatchRead#batch()}에 넣어 넘긴다.
 * <ol>
 *   <li>확정 조건(정상 수집, 줄이 있는 성공, 수신 뒤 90초 안 착수)을 만족하지 않는 배치는 상태에 넣지 않고 그 사유
 *       ({@code LATE}, {@code EMPTY}, {@code INPUT_UNAVAILABLE})만 실행 줄로 남긴다.</li>
 *   <li>이 노선에서 더 늦게 수신한 배치가 이미 이력에 들어갔으면 이 배치를 이력에 넣지 않고 아무것도 쓰지 않는다
 *       ({@link Status#OUT_OF_ORDER}).</li>
 *   <li>노선 상태가 없으면 이 배치 직전까지 되감아 만든다({@link RouteStateReplayer}). 되감기가 실패하면 그 노선은 착수 시각 기준
 *       {@value #REPLAY_RETRY_MINUTES}분 동안 되감지 않고 예보 없이 {@code INPUT_UNAVAILABLE} 실행 줄만 남긴다.</li>
 *   <li>{@link ForecastStage#advance}로 상태를 전진시키고 예측 조립 객체({@link AlightingAwarePredictionAssembler})로 예측을
 *       만든다. 출퇴근 범위 판정의 기준 시각은 이 배치의 착수 시각이다. 이미 반영한 배치면 아무것도 쓰지 않는다.</li>
 *   <li>예보 실행·버스별 기록·닫힌 통과를 한 트랜잭션으로 쓰고, 커밋한 뒤에만 상태를 바꾼다. 같은 배치의 실행 줄이 이미 있으면
 *       저장을 되돌리고 상태를 버린다({@link Status#DUPLICATE}). 실행 줄에는 뒤 단계에 넘기는 것과 같은 수집 사건의 수집 구멍
 *       여부와 관측 줄 수를 함께 적는다. 채점이 다시 뜰 때 이 값으로 그날의 구멍과 줄 수를 되살린다.</li>
 *   <li>사건을 수집 → 통과 → 예보 순서로 {@link PipelineEvents}에 넘긴다.</li>
 * </ol>
 * 예외는 그대로 던진다. 부르는 쪽이 그 노선의 상태를 버린다. {@code route} 행 잠금을 잡지 않고 {@code observation_batch}에 쓰지
 * 않는다. 한 노선의 처리는 한 스레드에서만 해야 한다.
 *
 * <p>되감기가 예보 단계를 돌리지 못하고 끝난 배치의 통과를 찾아 주면({@link RouteStateReplayer.Result#recoveredPasses}) 아직
 * 저장되지 않은 것만 이 배치와 같은 트랜잭션으로 저장하고, 이 배치의 사건보다 먼저 통과 사건으로 넘긴다. 그 통과의 닫힌 시각은 이미
 * 넘긴 사건들의 시각보다 이르다. 채점 단계는 통과를 닫힌 시각으로 정렬해 두고 직전 통과를 찾으므로 같은 직전 통과와 같은 정답을 낸다.
 */
public final class PipelineBatchProcessor {
    /** 되감기가 실패한 노선을 다시 되감기까지 기다리는 분. 배치의 착수 시각 기준이다. */
    public static final long REPLAY_RETRY_MINUTES = 5;
    static final Duration REPLAY_RETRY = Duration.ofMinutes(REPLAY_RETRY_MINUTES);
    private static final Logger LOGGER = LoggerFactory.getLogger(PipelineBatchProcessor.class);
    private static final Set<String> REPLAY_EXCLUSIONS = Set.of(ForecastStage.TRIP_START_UNKNOWN,
        ForecastStage.REPLAY_HISTORY_INCOMPLETE);

    private final String name;
    private final ForecastStage stage;
    private final RouteStateReplayer replayer;
    private final ForecastRecordStore records;
    private final StopPassStore passes;
    private final TransactionOperations transactions;
    private final List<PipelineEvents> listeners;
    private final RouteStateRegistry states;
    private final boolean rushOnly;

    /**
     * @param name 로그 사건 이름의 앞부분. 예: {@code pipeline_forecast}
     * @param transactions 저장 트랜잭션
     * @param listeners 커밋 뒤 사건을 받을 뒤 단계
     * @param states 노선 상태 등록부. 이 처리기가 상태를 읽고 바꾼다
     * @param rushOnly 설정 {@code forecast.alighting.rush-only}. 시간 확장 모델이 없을 때 출퇴근 범위 밖 배치를 예보하지
     *                 않는다
     */
    public PipelineBatchProcessor(
        String name,
        ForecastStage stage,
        RouteStateReplayer replayer,
        ForecastRecordStore records,
        StopPassStore passes,
        TransactionOperations transactions,
        List<PipelineEvents> listeners,
        RouteStateRegistry states,
        final boolean rushOnly
    ) {
        this.name = Objects.requireNonNull(name);
        this.stage = Objects.requireNonNull(stage);
        this.replayer = Objects.requireNonNull(replayer);
        this.records = Objects.requireNonNull(records);
        this.passes = Objects.requireNonNull(passes);
        this.transactions = Objects.requireNonNull(transactions);
        this.listeners = List.copyOf(listeners);
        this.states = Objects.requireNonNull(states);
        this.rushOnly = rushOnly;
    }

    /** 이 처리기가 읽고 바꾸는 노선 상태 등록부. */
    public RouteStateRegistry states() {
        return states;
    }

    /**
     * 배치 하나를 처리한다.
     *
     * @param context 노선 판본의 모델 문맥
     * @param read 배치와 배치 밖 값. 부르는 쪽이 이 처리에서 찍은 착수 시각을 배치에 넣어 넘긴다. 이 시각이 상태와 90초 판정의
     *     기준이고 실행 줄에 그대로 적힌다. 메모리의 상태가 든 시각과 다시 뜬 뒤 DB에서 읽는 시각이 같도록 저장
     *     자릿수(마이크로초)로 자른 값이어야 한다
     * @return 처리 결과
     */
    public Processed process(RouteContext context, ObservationBatchRead read) {
        AlightingAwareRouteStatistics statistics = context.statistics();
        ForecastBatch batch = read.batch();
        Resolution resolution = resolve(context, read);
        Replay replay = resolution.replay();
        if (resolution.skipped() != null) {
            return Processed.unsaved(resolution.skipped(), null, Map.of(), replay);
        }
        Outcome outcome = resolution.outcome();
        ForecastPublished published = new ForecastPublished(SourceDates.of(read.scheduledAt()),
            batch.observationBatchId(), read.routeId(), batch.routeVersionId(), batch.receivedAt(),
            batch.confirmedAt(), context.runtime().deploymentId(), outcome.outcome(), outcome.vehicles());
        List<PassClosed> closed = outcome.passes().stream()
            .map(pass -> pass.toEvent(published.sourceDate(), read.routeId()))
            .toList();
        List<PassClosed> recovered = withoutStored(replay.recovered());
        ObservationBatchSeen seen = seen(read);
        final boolean saved = Boolean.TRUE.equals(transactions.execute(status -> {
            if (!records.save(published, seen)) {
                status.setRollbackOnly();
                return false;
            }
            savePasses(closed);
            savePasses(recovered);
            return true;
        }));
        if (!saved) {
            states.remove(statistics.route());
            return Processed.unsaved(Status.DUPLICATE, published, outcome.excluded(), replay);
        }
        applyState(statistics, outcome);
        if (batch.confirmed()) {
            states.applied(statistics.route(), batch.receivedAt());
        }
        logRecovered(statistics, batch, replay, recovered);
        publish(seen, published, closed, recovered);
        return new Processed(Status.SAVED, published, closed.size(), outcome.excluded(), outcome.special(),
            replay.succeeded(), outcome.unattributed(), recovered.size(), replay.extension());
    }

    private Resolution resolve(RouteContext context, ObservationBatchRead read) {
        ForecastBatch batch = read.batch();
        if (!batch.confirmed()) {
            return Resolution.of(Outcome.withoutState(notConfirmed(batch), null), Replay.NONE);
        }
        AlightingAwareRouteStatistics statistics = context.statistics();
        if (states.receivedBeforeApplied(statistics.route(), batch.receivedAt())) {
            return Resolution.skipped(Status.OUT_OF_ORDER, Replay.NONE);
        }
        Optional<RouteForecastState> state = states.find(statistics);
        if (state.isEmpty() && states.replayDeferred(statistics.route(), batch.confirmedAt())) {
            return Resolution.of(Outcome.withoutState(ForecastOutcome.INPUT_UNAVAILABLE, "replay_deferred"),
                Replay.NONE);
        }
        Replay replay = Replay.NONE;
        if (state.isEmpty()) {
            RouteStateReplayer.Result replayed = replayer.replay(statistics, batch.confirmedAt(),
                batch.observationBatchId() - 1);
            replay = Replay.of(replayed);
            if (!replayed.succeeded()) {
                LOGGER.warn("event={}_replay_failed route={} batchId={} reason={}", name, statistics.route(),
                    batch.observationBatchId(), replayed.failureReason());
                states.deferReplay(statistics.route(), batch.confirmedAt().plus(REPLAY_RETRY));
                Outcome failed = Outcome.withoutState(ForecastOutcome.INPUT_UNAVAILABLE, "replay_failed");
                return Resolution.of(failed, replay);
            }
            state = Optional.of(replayed.state());
        }
        RouteForecastState current = state.orElseThrow();
        if (current.receivedBeforeApplied(batch)) {
            return Resolution.skipped(Status.OUT_OF_ORDER, replay);
        }
        ForecastStageResult result = stage.advance(current, statistics, read.capacitySourceId(), batch);
        if (result.status() == ForecastStageResult.Status.STALE) {
            return Resolution.skipped(Status.STALE, replay);
        }
        return Resolution.of(advanced(context, read, result), replay);
    }

    private void savePasses(List<PassClosed> closed) {
        if (!closed.isEmpty()) {
            passes.save(closed);
        }
    }

    /**
     * 되감으며 찾은 통과 가운데 아직 저장되지 않은 것만 남긴다. 같은 실행 줄은 다음 되감기 창에도 들어 있어 같은 통과를 다시 찾는다.
     * 이미 저장된 통과는 채점이 받았거나 채점의 되감기가 읽으므로 다시 넘기지 않는다.
     */
    private List<PassClosed> withoutStored(List<PassClosed> recovered) {
        if (recovered.isEmpty()) {
            return recovered;
        }
        Set<PassKey> stored = new HashSet<>();
        for (Map.Entry<Long, List<PassClosed>> route : byRoute(recovered).entrySet()) {
            Instant from = route.getValue().getFirst().closedAt();
            Instant until = route.getValue().getLast().closedAt().plus(1, ChronoUnit.MICROS);
            passes.findClosedBetween(route.getKey(), from, until).forEach(pass -> stored.add(PassKey.of(pass)));
        }
        return recovered.stream().filter(pass -> !stored.contains(PassKey.of(pass))).toList();
    }

    /** 닫힌 순서를 지키면서 노선별로 묶는다. 뒤 단계는 통과를 노선 하나씩 받는다. */
    private static Map<Long, List<PassClosed>> byRoute(List<PassClosed> closed) {
        Map<Long, List<PassClosed>> byRoute = new LinkedHashMap<>();
        for (PassClosed pass : closed) {
            byRoute.computeIfAbsent(pass.routeId(), ignored -> new ArrayList<>()).add(pass);
        }
        return byRoute;
    }

    private void logRecovered(
        AlightingAwareRouteStatistics statistics,
        ForecastBatch batch,
        Replay replay,
        List<PassClosed> recovered
    ) {
        if (replay.recovered().isEmpty()) {
            return;
        }
        LOGGER.info("event={}_passes_recovered route={} batchId={} found={} saved={}", name, statistics.route(),
            batch.observationBatchId(), replay.recovered().size(), recovered.size());
    }

    private void applyState(AlightingAwareRouteStatistics statistics, Outcome outcome) {
        if (outcome.dropState()) {
            states.remove(statistics.route());
            return;
        }
        if (outcome.next() != null) {
            states.put(statistics, outcome.next());
        }
    }

    private Outcome advanced(RouteContext context, ObservationBatchRead read, ForecastStageResult result) {
        return switch (result.status()) {
            case REPLAY_REQUIRED -> new Outcome(ForecastOutcome.INPUT_UNAVAILABLE, List.of(), List.of(), Map.of(), null,
                true, null, 0);
            case INPUT_UNAVAILABLE -> new Outcome(ForecastOutcome.INPUT_UNAVAILABLE, List.of(), List.of(), Map.of(),
                result.state(), false, null, 0);
            case NOT_CONFIRMED -> Outcome.withoutState(notConfirmed(read.batch()), null);
            case STALE -> throw new IllegalStateException("이미 반영한 배치는 처리 결과를 만들지 않는다");
            case FORECAST -> forecast(context, read, result);
        };
    }

    private Outcome forecast(RouteContext context, ObservationBatchRead read, ForecastStageResult result) {
        Map<Long, String> excluded = new HashMap<>();
        result.withheld().stream()
            .filter(vehicle -> REPLAY_EXCLUSIONS.contains(vehicle.reason()))
            .forEach(vehicle -> excluded.put(vehicle.observationId(), vehicle.reason()));
        if (outsideScope(context, read.batch())) {
            return new Outcome(ForecastOutcome.OUT_OF_SCOPE, List.of(), result.closedPasses(), excluded, result.state(),
                false, null, result.unattributedPasses());
        }
        List<ForecastPublished.VehicleForecast> vehicles = vehicles(context, read, result);
        ForecastOutcome outcome = publishedOutcome(vehicles);
        return new Outcome(outcome, vehicles, result.closedPasses(), excluded, result.state(), false, null,
            result.unattributedPasses());
    }

    private static ForecastOutcome publishedOutcome(List<ForecastPublished.VehicleForecast> vehicles) {
        if (vehicles.isEmpty()) {
            return ForecastOutcome.EMPTY;
        }
        return ForecastOutcome.PUBLISHED;
    }

    /** 시간 확장 모델이 없을 때 출퇴근 범위 밖인지다. 기준 시각은 이 배치의 착수 시각이다. */
    private boolean outsideScope(RouteContext context, ForecastBatch batch) {
        if (!rushOnly || context.runtime().model().supportsExtendedHours()) {
            return false;
        }
        return !context.statistics().timeCell(batch.confirmedAt().atZone(SourceDates.KOREA)).isRush();
    }

    private static List<ForecastPublished.VehicleForecast> vehicles(
        RouteContext context,
        ObservationBatchRead read,
        ForecastStageResult result
    ) {
        AlightingAwarePredictionAssembler assembler = new AlightingAwarePredictionAssembler(context.runtime().model(),
            context.statistics(), context.stops());
        List<ForecastPublished.VehicleForecast> vehicles = new ArrayList<>();
        for (AlightingAwareFeatureSnapshot snapshot : result.snapshots()) {
            Optional<AlightingAwarePredictionAssembler.VehiclePrediction> prediction = assembler.predict(snapshot);
            if (prediction.isEmpty() || prediction.get().targets().isEmpty()) {
                continue;
            }
            List<AlightingAwarePredictionAssembler.TargetPrediction> targets = prediction.get().targets();
            int[] stops = new int[targets.size()];
            double[] seats = new double[targets.size()];
            double[] probabilities = new double[targets.size()];
            for (int index = 0; index < targets.size(); index++) {
                SeatForecastResult value = targets.get(index).result();
                stops[index] = targets.get(index).target().stopOrder();
                seats[index] = value.expectedSeats();
                probabilities[index] = value.fullChance();
            }
            RouteForecastState.VehicleTrack track = result.state().vehicles().get(snapshot.source().vehicleId());
            Integer rowNumber = read.sourceRowNumbers().get(snapshot.vehicleObservationId());
            vehicles.add(new ForecastPublished.VehicleForecast(snapshot.vehicleObservationId(),
                rowNumberOrZero(rowNumber), snapshot.source().vehicleId(), snapshot.source().passedStopOrder(),
                snapshot.provenance().tripStartObservationId(), track.chainStartObservationId(),
                snapshot.source().down(), snapshot.capacity(), prediction.get().extended(), stops, seats,
                probabilities));
        }
        vehicles.sort(Comparator.comparingInt(ForecastPublished.VehicleForecast::sourceRowNumber)
            .thenComparingLong(ForecastPublished.VehicleForecast::vehicleObservationId));
        return vehicles;
    }

    private static int rowNumberOrZero(Integer rowNumber) {
        if (rowNumber == null) {
            return 0;
        }
        return rowNumber;
    }

    /** 확정 조건을 만족하지 않는 배치의 사유다. 정상 수집이 아니거나 시각이 빠진 배치는 입력 부족으로 본다. */
    private static ForecastOutcome notConfirmed(ForecastBatch batch) {
        if (lacksLiveTiming(batch)) {
            return ForecastOutcome.INPUT_UNAVAILABLE;
        }
        if (lacksStoredRows(batch)) {
            return ForecastOutcome.EMPTY;
        }
        return ForecastOutcome.LATE;
    }

    private static boolean lacksLiveTiming(ForecastBatch batch) {
        return !"LIVE".equals(batch.ingestionOrigin()) || batch.confirmedAt() == null || batch.receivedAt() == null;
    }

    private static boolean lacksStoredRows(ForecastBatch batch) {
        return !"SUCCESS_ROWS".equals(batch.outcome()) || batch.storedRows() == null || batch.storedRows() <= 0;
    }

    private static ObservationBatchSeen seen(ObservationBatchRead read) {
        ForecastBatch batch = read.batch();
        CollectionResult result = switch (batch.outcome()) {
            case "SUCCESS_ROWS" -> CollectionResult.SUCCESS;
            case "SUCCESS_EMPTY" -> CollectionResult.EMPTY;
            default -> CollectionResult.FAILED;
        };
        return new ObservationBatchSeen(batch.observationBatchId(), read.routeId(), batch.routeVersionId(),
            read.scheduledAt(), batch.receivedAt(), result, storedRowsOrZero(batch),
            batch.rows().size());
    }

    private static int storedRowsOrZero(ForecastBatch batch) {
        if (batch.storedRows() == null) {
            return 0;
        }
        return batch.storedRows();
    }

    private void publish(
        ObservationBatchSeen seen,
        ForecastPublished published,
        List<PassClosed> closed,
        List<PassClosed> recovered
    ) {
        Collection<List<PassClosed>> recoveredByRoute = byRoute(recovered).values();
        for (PipelineEvents listener : listeners) {
            try {
                recoveredByRoute.forEach(listener::passesClosed);
                listener.batchSeen(seen);
                if (!closed.isEmpty()) {
                    listener.passesClosed(closed);
                }
                listener.forecastPublished(published);
            } catch (RuntimeException failure) {
                LOGGER.warn("event=pipeline_event_listener_failed listener={} batchId={} exceptionType={}",
                    listener.getClass().getSimpleName(), published.observationBatchId(),
                    failure.getClass().getSimpleName(), failure);
            }
        }
    }

    /** 처리 결과의 종류다. */
    public enum Status {
        /** 실행 줄을 저장하고 사건을 넘겼다. */
        SAVED,
        /** 이미 반영한 배치라 아무것도 쓰지 않았다. */
        STALE,
        /** 같은 배치의 실행 줄이 이미 있어 저장을 되돌리고 상태를 버렸다. */
        DUPLICATE,
        /**
         * 이 노선에서 더 늦게 수신한 배치가 이미 이력에 들어가 있어 아무것도 쓰지 않았다. 실행 줄이 없으므로 다시 주워지고,
         * 수신 뒤 90초가 지나면 늦음으로 닫힌다.
         */
        OUT_OF_ORDER
    }

    /**
     * 배치 하나의 처리 결과다.
     *
     * @param status 처리 결과의 종류
     * @param published 저장한(또는 저장하려던) 예보 실행. {@link Status#STALE}과 {@link Status#OUT_OF_ORDER}면 없다
     * @param closedPasses 저장한 닫힌 통과 수
     * @param exclusions 되감은 직후라 예보에서 뺀 관측과 사유({@code TRIP_START_UNKNOWN}, {@code REPLAY_HISTORY_INCOMPLETE})
     * @param special 실행 결과와 따로 정해진 배치 분류({@code replay_deferred}, {@code replay_failed}). 없으면 비어 있다
     * @param replayed 이 처리에서 되감았으면 성공 여부, 되감지 않았으면 비어 있다
     * @param unattributedPasses 이 배치가 닫았지만 연결 시작을 보지 못한 차량이라 저장하지 않은 통과 수
     * @param recoveredPasses 되감으며 찾아 이 배치와 함께 저장한, 예보 단계를 돌리지 못하고 끝난 배치의 통과 수
     * @param replayExtension 이 처리의 되감기가 창을 늘려 다시 되감았는지
     */
    public record Processed(
        Status status,
        ForecastPublished published,
        int closedPasses,
        Map<Long, String> exclusions,
        String special,
        Boolean replayed,
        int unattributedPasses,
        int recoveredPasses,
        RouteStateReplayer.Extension replayExtension
    ) {
        public Processed {
            Objects.requireNonNull(status);
            Objects.requireNonNull(replayExtension);
            exclusions = Map.copyOf(exclusions);
        }

        private static Processed unsaved(
            Status status,
            ForecastPublished published,
            Map<Long, String> exclusions,
            Replay replay
        ) {
            return new Processed(status, published, 0, exclusions, null, replay.succeeded(), 0, 0,
                replay.extension());
        }

        /** 되감은 직후라 예보에서 뺀 관측. */
        public Set<Long> excluded() {
            return exclusions.keySet();
        }

        /** 저장한 버스별 예측. 저장하지 않았으면 빈 목록이다. */
        public List<ForecastPublished.VehicleForecast> vehicles() {
            if (published == null) {
                return List.of();
            }
            return published.vehicles();
        }
    }

    /**
     * 배치 하나의 처리 결과다. 다음 상태가 없으면 상태를 바꾸지 않고, {@code dropState}면 상태를 버린다.
     * {@code special}은 실행 결과와 따로 정해진 배치 분류다. {@code unattributed}는 저장하지 않은 통과 수다.
     */
    private record Outcome(
        ForecastOutcome outcome,
        List<ForecastPublished.VehicleForecast> vehicles,
        List<ClosedPass> passes,
        Map<Long, String> excluded,
        RouteForecastState next,
        boolean dropState,
        String special,
        int unattributed
    ) {
        static Outcome withoutState(ForecastOutcome outcome, String special) {
            return new Outcome(outcome, List.of(), List.of(), Map.of(), null, false, special, 0);
        }
    }

    /**
     * 상태를 쓰기 전까지의 판정이다. {@code skipped}가 있으면 아무것도 쓰지 않는 배치라 결과가 없다
     * ({@link Status#STALE}, {@link Status#OUT_OF_ORDER}).
     */
    private record Resolution(Outcome outcome, Replay replay, Status skipped) {
        static Resolution of(Outcome outcome, Replay replay) {
            return new Resolution(outcome, replay, null);
        }

        static Resolution skipped(Status status, Replay replay) {
            return new Resolution(null, replay, status);
        }
    }

    /**
     * 이 처리에서 한 되감기다. {@code succeeded}는 되감았으면 성공 여부, 되감지 않았으면 비어 있다.
     * {@code recovered}는 되감으며 찾은, 예보 단계를 돌리지 못하고 끝난 배치의 통과다.
     */
    private record Replay(Boolean succeeded, List<PassClosed> recovered, RouteStateReplayer.Extension extension) {
        static final Replay NONE = new Replay(null, List.of(), RouteStateReplayer.Extension.NONE);

        static Replay of(RouteStateReplayer.Result result) {
            return new Replay(result.succeeded(), result.recoveredPasses(), result.extension());
        }
    }

    /** {@code stop_pass}의 기본 키다. */
    private record PassKey(LocalDate sourceDate, long routeId, long tripStartObservationId, int stopOrder) {
        static PassKey of(PassClosed pass) {
            return new PassKey(pass.sourceDate(), pass.routeId(), pass.tripStartObservationId(), pass.stopOrder());
        }
    }
}
