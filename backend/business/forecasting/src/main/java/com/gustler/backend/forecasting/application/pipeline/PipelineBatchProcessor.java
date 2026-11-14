package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.api.pipeline.CollectionResult;
import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished.VehicleForecast;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePredictionAssembler;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePredictionAssembler.VehiclePrediction;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.pipeline.SourceDates;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 관측 배치 하나를 새 경로로 처리하는 공통 단계다. 그림자 실행({@link ShadowForecastService})과 본선({@link ForecastPipelineService})이
 * 같은 이 코드를 쓴다. 둘이 다른 것은 착수 시각의 출처와 옛 결과와의 대조뿐이다.
 *
 * <p>한 번의 처리는 다음 순서다. 착수 시각은 부르는 쪽이 {@link ObservationBatchRead#batch()}에 넣어 넘긴다.
 * <ol>
 *   <li>옛 경로의 확정 조건(정상 수집, 줄이 있는 성공, 수신 뒤 90초 안 착수)을 만족하지 않는 배치는 상태에 넣지 않고 그 사유
 *       ({@code LATE}, {@code EMPTY}, {@code INPUT_UNAVAILABLE})만 실행 줄로 남긴다.</li>
 *   <li>노선 상태가 없으면 이 배치 직전까지 되감아 만든다({@link RouteStateReplayer}). 되감기가 실패하면 그 노선은 착수 시각 기준
 *       {@value #REPLAY_RETRY_MINUTES}분 동안 되감지 않고 예보 없이 {@code INPUT_UNAVAILABLE} 실행 줄만 남긴다.</li>
 *   <li>{@link ForecastStage#advance}로 상태를 전진시키고, 옛 경로와 같은 예측 조립 객체({@link AlightingAwarePredictionAssembler})로
 *       예측을 만든다. 출퇴근 범위 판정은 옛 경로의 입력 조회와 같은 기준(이 배치의 착수 시각)이다. 이미 반영한 배치면 아무것도 쓰지
 *       않는다.</li>
 *   <li>예보 실행·버스별 기록·닫힌 통과를 한 트랜잭션으로 쓰고, 커밋한 뒤에만 상태를 바꾼다.</li>
 *   <li>사건을 수집 → 통과 → 예보 순서로 {@link PipelineEvents}에 넘긴다.</li>
 * </ol>
 * 예외는 그대로 던진다. 부르는 쪽이 그 노선의 상태를 버린다. {@code route} 행 잠금을 잡지 않고 {@code observation_batch}와 옛 경로의
 * 테이블에 쓰지 않는다. 한 노선의 처리는 한 스레드에서만 해야 한다.
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
     * @param name 로그 사건 이름의 앞부분. 예: {@code pipeline_shadow}
     * @param transactions 저장 트랜잭션
     * @param listeners 커밋 뒤 사건을 받을 뒤 단계
     * @param states 노선 상태 등록부. 이 처리기가 상태를 읽고 바꾼다
     * @param rushOnly 옛 경로의 {@code forecast.alighting.rush-only}. 시간 확장 모델이 없을 때 출퇴근 범위 밖 배치를 예보하지 않는다
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
     * @param read 배치와 배치 밖 값. 배치의 착수 시각이 상태와 90초 판정의 기준이다
     * @param fallbackConfirmedAt 배치의 착수 시각이 비어 있을 때 실행 줄에 적는 시각
     * @param requireNewRun 참이면 같은 배치의 실행 줄이 이미 있을 때 저장을 되돌리고 상태를 버린다
     * @return 처리 결과
     */
    public Processed process(
        RouteContext context,
        ObservationBatchRead read,
        Instant fallbackConfirmedAt,
        final boolean requireNewRun
    ) {
        AlightingAwareRouteStatistics statistics = context.statistics();
        ForecastBatch batch = read.batch();
        Outcome outcome = null;
        Boolean replayed = null;
        if (!batch.confirmed()) {
            outcome = Outcome.withoutState(notConfirmed(batch), null);
        } else {
            Optional<RouteForecastState> state = states.find(statistics);
            if (state.isEmpty() && states.replayDeferred(statistics.route(), batch.confirmedAt())) {
                outcome = Outcome.withoutState(ForecastOutcome.INPUT_UNAVAILABLE, "replay_deferred");
            } else if (state.isEmpty()) {
                RouteStateReplayer.Result result = replayer.replay(statistics, batch.confirmedAt(),
                    batch.observationBatchId() - 1);
                replayed = result.succeeded();
                if (result.succeeded()) {
                    state = Optional.of(result.state());
                } else {
                    LOGGER.warn("event={}_replay_failed route={} batchId={} reason={}", name, statistics.route(),
                        batch.observationBatchId(), result.failureReason());
                    states.deferReplay(statistics.route(), batch.confirmedAt().plus(REPLAY_RETRY));
                    outcome = Outcome.withoutState(ForecastOutcome.INPUT_UNAVAILABLE, "replay_failed");
                }
            }
            if (outcome == null) {
                ForecastStageResult result = stage.advance(state.orElseThrow(), statistics, read.capacitySourceId(), batch);
                if (result.status() == ForecastStageResult.Status.STALE) {
                    return new Processed(Status.STALE, null, 0, Map.of(), null, replayed);
                }
                outcome = advanced(context, read, result);
            }
        }
        ForecastPublished published = new ForecastPublished(SourceDates.of(read.scheduledAt()),
            batch.observationBatchId(), read.routeId(), batch.routeVersionId(), batch.receivedAt(),
            batch.confirmedAt() == null ? fallbackConfirmedAt : batch.confirmedAt(), context.runtime().deploymentId(),
            outcome.outcome(), outcome.vehicles());
        List<PassClosed> closed = outcome.passes().stream()
            .map(pass -> pass.toEvent(published.sourceDate(), read.routeId()))
            .toList();
        final boolean saved = Boolean.TRUE.equals(transactions.execute(status -> {
            if (!records.save(published) && requireNewRun) {
                status.setRollbackOnly();
                return false;
            }
            if (!closed.isEmpty()) {
                passes.save(closed);
            }
            return true;
        }));
        if (!saved) {
            states.remove(statistics.route());
            return new Processed(Status.DUPLICATE, published, 0, outcome.excluded(), null, replayed);
        }
        if (outcome.dropState()) {
            states.remove(statistics.route());
        } else if (outcome.next() != null) {
            states.put(statistics, outcome.next());
        }
        publish(seen(read), published, closed);
        return new Processed(Status.SAVED, published, closed.size(), outcome.excluded(), outcome.special(), replayed);
    }

    private Outcome advanced(RouteContext context, ObservationBatchRead read, ForecastStageResult result) {
        return switch (result.status()) {
            case REPLAY_REQUIRED -> new Outcome(ForecastOutcome.INPUT_UNAVAILABLE, List.of(), List.of(), Map.of(), null,
                true, null);
            case INPUT_UNAVAILABLE -> new Outcome(ForecastOutcome.INPUT_UNAVAILABLE, List.of(), List.of(), Map.of(),
                result.state(), false, null);
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
                false, null);
        }
        List<VehicleForecast> vehicles = vehicles(context, read, result);
        ForecastOutcome outcome = vehicles.isEmpty() ? ForecastOutcome.EMPTY : ForecastOutcome.PUBLISHED;
        return new Outcome(outcome, vehicles, result.closedPasses(), excluded, result.state(), false, null);
    }

    /** 옛 경로의 입력 조회({@code validateScope})와 같다. 기준 시각은 이 배치의 착수 시각이다. */
    private boolean outsideScope(RouteContext context, ForecastBatch batch) {
        if (!rushOnly || context.runtime().model().supportsExtendedHours()) {
            return false;
        }
        return !context.statistics().timeCell(batch.confirmedAt().atZone(SourceDates.KOREA)).isRush();
    }

    private static List<VehicleForecast> vehicles(
        RouteContext context,
        ObservationBatchRead read,
        ForecastStageResult result
    ) {
        AlightingAwarePredictionAssembler assembler = new AlightingAwarePredictionAssembler(context.runtime().model(),
            context.statistics(), context.stops());
        List<VehicleForecast> vehicles = new ArrayList<>();
        for (AlightingAwareFeatureSnapshot snapshot : result.snapshots()) {
            Optional<VehiclePrediction> prediction = assembler.predict(snapshot);
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
            vehicles.add(new VehicleForecast(snapshot.vehicleObservationId(), rowNumber == null ? 0 : rowNumber,
                snapshot.source().vehicleId(), snapshot.source().passedStopOrder(),
                snapshot.provenance().tripStartObservationId(), track.chainStartObservationId(), snapshot.source().down(),
                snapshot.capacity(), prediction.get().extended(), stops, seats, probabilities));
        }
        vehicles.sort(Comparator.comparingInt(VehicleForecast::sourceRowNumber)
            .thenComparingLong(VehicleForecast::vehicleObservationId));
        return vehicles;
    }

    /** 확정 조건을 만족하지 않는 배치의 사유다. 정상 수집이 아닌 배치는 옛 경로가 확정하지 않고 실패한다. */
    private static ForecastOutcome notConfirmed(ForecastBatch batch) {
        if (!"LIVE".equals(batch.ingestionOrigin()) || batch.confirmedAt() == null || batch.receivedAt() == null) {
            return ForecastOutcome.INPUT_UNAVAILABLE;
        }
        if (!"SUCCESS_ROWS".equals(batch.outcome()) || batch.storedRows() == null || batch.storedRows() <= 0) {
            return ForecastOutcome.EMPTY;
        }
        return ForecastOutcome.LATE;
    }

    private static ObservationBatchSeen seen(ObservationBatchRead read) {
        ForecastBatch batch = read.batch();
        CollectionResult result = switch (batch.outcome()) {
            case "SUCCESS_ROWS" -> CollectionResult.SUCCESS;
            case "SUCCESS_EMPTY" -> CollectionResult.EMPTY;
            default -> CollectionResult.FAILED;
        };
        return new ObservationBatchSeen(batch.observationBatchId(), read.routeId(), batch.routeVersionId(),
            read.scheduledAt(), batch.receivedAt(), result, batch.storedRows() == null ? 0 : batch.storedRows(),
            batch.rows().size());
    }

    private void publish(ObservationBatchSeen seen, ForecastPublished published, List<PassClosed> closed) {
        for (PipelineEvents listener : listeners) {
            try {
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
        DUPLICATE
    }

    /**
     * 배치 하나의 처리 결과다.
     *
     * @param status 처리 결과의 종류
     * @param published 저장한(또는 저장하려던) 예보 실행. {@link Status#STALE}이면 없다
     * @param closedPasses 저장한 닫힌 통과 수
     * @param exclusions 되감은 직후라 예보에서 뺀 관측과 사유({@code TRIP_START_UNKNOWN}, {@code REPLAY_HISTORY_INCOMPLETE})
     * @param special 실행 결과와 따로 정해진 배치 분류({@code replay_deferred}, {@code replay_failed}). 없으면 비어 있다
     * @param replayed 이 처리에서 되감았으면 성공 여부, 되감지 않았으면 비어 있다
     */
    public record Processed(
        Status status,
        ForecastPublished published,
        int closedPasses,
        Map<Long, String> exclusions,
        String special,
        Boolean replayed
    ) {
        public Processed {
            Objects.requireNonNull(status);
            exclusions = Map.copyOf(exclusions);
        }

        /** 되감은 직후라 예보에서 뺀 관측. */
        public Set<Long> excluded() {
            return exclusions.keySet();
        }

        /** 저장한 버스별 예측. 저장하지 않았으면 빈 목록이다. */
        public List<VehicleForecast> vehicles() {
            return published == null ? List.of() : published.vehicles();
        }
    }

    /**
     * 배치 하나의 새 경로 결과다. 다음 상태가 없으면 상태를 바꾸지 않고, {@code dropState}면 상태를 버린다.
     * {@code special}은 대조와 무관하게 정해진 배치 분류다.
     */
    private record Outcome(
        ForecastOutcome outcome,
        List<VehicleForecast> vehicles,
        List<ClosedPass> passes,
        Map<Long, String> excluded,
        RouteForecastState next,
        boolean dropState,
        String special
    ) {
        static Outcome withoutState(ForecastOutcome outcome, String special) {
            return new Outcome(outcome, List.of(), List.of(), Map.of(), null, false, special);
        }
    }
}
