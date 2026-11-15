package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareBatchContext;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareHistoryCompactor;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 확정 판본 하차 모델의 관측 배치 하나를 노선 상태 위에서 처리한다. 상태는 메모리에 있고 파일·DB에 쓰지 않는다.
 *
 * <p>상태의 관측·사건에 이번 배치 관측을 더해 {@link AlightingAwareHistoryCompactor#resolve}로 통과 사건을 만들고,
 * 이번 배치의 좌석을 아는 차량마다 정원을 확인해 {@link AlightingAwareBatchContext}를 공유하는 스냅샷을 만든 뒤,
 * {@link AlightingAwareHistoryCompactor#compact}로 보존 창을 줄여 새 상태를 만든다. 계산식은 도메인 객체의 것을 그대로 쓴다.
 *
 * <p>노선 판본이 활성 판본인지, 출퇴근 범위 안인지는 호출한 쪽이 정한다. 이 클래스는 상태를 바꾸지 않으며 스레드 안전하다.
 * 단, 돌려준 스냅샷은 한 스레드에서만 쓴다.
 */
public final class ForecastStage {
    public static final String BATCH_NOT_CONFIRMED = "BATCH_NOT_CONFIRMED";
    public static final String BATCH_ALREADY_APPLIED = "BATCH_ALREADY_APPLIED";
    public static final String SOURCE_ROWS_MISSING = "ALIGHTING_AWARE_SOURCE_ROWS_MISSING";
    public static final String AMBIGUOUS_PASS = "ALIGHTING_AWARE_AMBIGUOUS_PASS";
    public static final String TRIP_START_UNKNOWN = "TRIP_START_UNKNOWN";
    public static final String REPLAY_HISTORY_INCOMPLETE = "REPLAY_HISTORY_INCOMPLETE";
    public static final String VEHICLE_CAPACITY_UNKNOWN = "VEHICLE_CAPACITY_UNKNOWN";
    public static final String VEHICLE_CAPACITY_OUTSIDE_MODEL_SCOPE = "VEHICLE_CAPACITY_OUTSIDE_MODEL_SCOPE";
    private static final int MINIMUM_CAPACITY = 30;
    private static final int MAXIMUM_SEATS = 70;
    private static final Comparator<AlightingAwareObservation> RETAINED_ORDER = Comparator
        .comparing(AlightingAwareObservation::receivedAt)
        .thenComparingLong(AlightingAwareObservation::batchId)
        .thenComparing(AlightingAwareObservation::vehicleId);

    private final VehicleCapacities capacities;

    public ForecastStage(VehicleCapacities capacities) {
        this.capacities = capacities;
    }

    /**
     * 배치 하나로 상태를 전진시키고 예보 입력을 만든다.
     * 확정 조건을 만족하지 않거나 이미 반영한 배치면 상태를 그대로 돌려준다. 줄 수가 저장 줄 수와 다르면 되감기를 요구한다.
     * 통과 판정이 모호하다고 실패하면 예보하지 않되, 관측은 상태에 넣어 다음 배치가 같은 이력을 다시 판정하게 한다.
     * 정원 조회가 실패하면 예외를 그대로 던지며, 받은 상태는 바뀌지 않는다.
     */
    public ForecastStageResult advance(
        RouteForecastState state,
        AlightingAwareRouteStatistics statistics,
        String capacitySourceId,
        ForecastBatch batch
    ) {
        return apply(state, statistics, batch, capacitySourceId, Mode.FORECAST);
    }

    /** 예보 없이 상태만 전진시킨다. 되감기가 쓴다. 정원을 조회하지 않고 통과 목록도 만들지 않는다. */
    ForecastStageResult replay(
        RouteForecastState state,
        AlightingAwareRouteStatistics statistics,
        ForecastBatch batch
    ) {
        return apply(state, statistics, batch, null, Mode.REPLAY);
    }

    /**
     * 예보 없이 상태를 전진시키면서 이 배치가 닫은 통과를 함께 돌려준다. 예보 단계를 돌리지 못하고 끝난 배치를 되감을 때 쓴다.
     * 통과의 키(운행 시작 관측)가 계속 돈 상태와 같아야 하므로 연결 시작과 운행 시작을 모두 본 차량의 통과만 만든다.
     */
    ForecastStageResult replayClosing(
        RouteForecastState state,
        AlightingAwareRouteStatistics statistics,
        ForecastBatch batch
    ) {
        return apply(state, statistics, batch, null, Mode.REPLAY_CLOSING);
    }

    private ForecastStageResult apply(
        RouteForecastState state,
        AlightingAwareRouteStatistics statistics,
        ForecastBatch batch,
        String capacitySourceId,
        Mode mode
    ) {
        requireSameRoute(state, statistics);
        if (!batch.confirmed()) {
            return ForecastStageResult.unchanged(ForecastStageResult.Status.NOT_CONFIRMED, BATCH_NOT_CONFIRMED, state);
        }
        if (!state.precedes(batch)) {
            return ForecastStageResult.unchanged(ForecastStageResult.Status.STALE, BATCH_ALREADY_APPLIED, state);
        }
        if (!batch.rowsComplete()) {
            return ForecastStageResult.unchanged(
                ForecastStageResult.Status.REPLAY_REQUIRED, SOURCE_ROWS_MISSING, state);
        }
        RouteForecastState.Cursor cursor = new RouteForecastState.Cursor(batch.observationBatchId(), state.nextRank(),
            batch.confirmedAt(), batch.receivedAt());
        List<AlightingAwareObservation> arrivals = observations(batch, cursor.rank(), statistics.turn());
        List<AlightingAwareObservation> history = new ArrayList<>(state.observations());
        history.addAll(arrivals);
        RouteForecastState.Progress progress = state.track(arrivals, statistics.realStopOrders());
        List<AlightingAwarePassEvent> events;
        try {
            events = AlightingAwareHistoryCompactor.resolve(
                new AlightingAwareHistoryCompactor.History(state.prefix(), history, state.events()),
                statistics.realStopOrders());
        } catch (IllegalArgumentException ambiguous) {
            RouteForecastState held = state.next(progress, state.prefix(), history, state.events(), cursor);
            return new ForecastStageResult(ForecastStageResult.Status.INPUT_UNAVAILABLE, AMBIGUOUS_PASS, held,
                List.of(), List.of(), 0, List.of());
        }
        List<AlightingAwareFeatureSnapshot> snapshots = new ArrayList<>();
        List<ForecastStageResult.WithheldVehicle> withheld = new ArrayList<>();
        if (mode == Mode.FORECAST) {
            snapshots(state, statistics, batch, capacitySourceId, progress, history, events, snapshots, withheld);
        }
        AlightingAwareHistoryCompactor.History compact = AlightingAwareHistoryCompactor.compact(
            new AlightingAwareHistoryCompactor.History(state.prefix(), history, events), batch.confirmedAt());
        List<AlightingAwareObservation> retained = new ArrayList<>(compact.observations());
        retained.sort(RETAINED_ORDER);
        RouteForecastState next = state.next(progress, compact.prefix(), retained, compact.events(), cursor);
        if (mode == Mode.REPLAY) {
            return new ForecastStageResult(ForecastStageResult.Status.FORECAST, null, next, List.of(), List.of(), 0,
                List.of());
        }
        List<ClosedPass> closed = new ArrayList<>();
        int unattributed = 0;
        for (AlightingAwarePassEvent event : events) {
            if (event.confirmation().batchId() != batch.observationBatchId()) {
                continue;
            }
            RouteForecastState.VehicleTrack track = progress.vehicle(event.vehicleId());
            if (lacksKnownStart(track, mode)) {
                unattributed++;
                continue;
            }
            closed.add(new ClosedPass(event, track.chainStartObservationId(), reachedKnownSeatAt(event, history)));
        }
        return new ForecastStageResult(ForecastStageResult.Status.FORECAST, null, next, snapshots, closed,
            unattributed, withheld);
    }

    private static boolean lacksKnownStart(RouteForecastState.VehicleTrack track, Mode mode) {
        if (!track.chainStartKnown()) {
            return true;
        }
        return mode == Mode.REPLAY_CLOSING && !track.tripStartKnown();
    }

    private void snapshots(
        RouteForecastState state,
        AlightingAwareRouteStatistics statistics,
        ForecastBatch batch,
        String capacitySourceId,
        RouteForecastState.Progress progress,
        List<AlightingAwareObservation> history,
        List<AlightingAwarePassEvent> events,
        List<AlightingAwareFeatureSnapshot> snapshots,
        List<ForecastStageResult.WithheldVehicle> withheld
    ) {
        List<AlightingAwareObservation> candidates = new ArrayList<>();
        final boolean complete = progress.historyCompleteAt(batch.confirmedAt());
        for (AlightingAwareObservation source : history) {
            if (!isEligibleSource(source, batch.observationBatchId())) {
                continue;
            }
            String historyReason = historyWithholdReason(complete, progress, source);
            if (historyReason != null) {
                withheld.add(withheld(source, historyReason));
                continue;
            }
            candidates.add(source);
        }
        if (candidates.isEmpty()) {
            return;
        }
        Map<String, Integer> known = capacities.knownAt(capacitySourceId,
            candidates.stream().map(AlightingAwareObservation::vehicleId).toList(), batch.confirmedAt(),
            batch.observationBatchId());
        AlightingAwareBatchContext context =
            new AlightingAwareBatchContext(statistics, history, events, state.prefix());
        for (AlightingAwareObservation source : candidates) {
            Integer capacity = known.get(source.vehicleId());
            String capacityReason = capacityWithholdReason(capacity);
            if (capacityReason != null) {
                withheld.add(withheld(source, capacityReason));
                continue;
            }
            snapshots.add(new AlightingAwareFeatureSnapshot(batch.routeVersionId(), source, capacity, context));
        }
    }

    private static String historyWithholdReason(
        final boolean complete,
        RouteForecastState.Progress progress,
        AlightingAwareObservation source
    ) {
        if (!complete) {
            return REPLAY_HISTORY_INCOMPLETE;
        }
        if (!progress.tripStartKnown(source.vehicleId())) {
            return TRIP_START_UNKNOWN;
        }
        return null;
    }

    private static String capacityWithholdReason(Integer capacity) {
        if (capacity == null) {
            return VEHICLE_CAPACITY_UNKNOWN;
        }
        if (capacity < MINIMUM_CAPACITY) {
            return VEHICLE_CAPACITY_OUTSIDE_MODEL_SCOPE;
        }
        return null;
    }

    /**
     * 줄을 관측으로 바꾼다.
     * 차량 번호가 없는 줄과, 같은 배치에 둘 이상 나온 차량의 줄은 모두 뺀다. 좌석은 0~70이고 모름 사유가 없을 때만 값이다.
     * 확정 시각은 착수 시각, 물리 시각은 수신 시각이며 방향은 통계의 회차 지점으로 정한다.
     */
    static List<AlightingAwareObservation> observations(ForecastBatch batch, final long rank, final int turn) {
        Map<String, Integer> rowsByVehicle = new HashMap<>();
        for (ForecastObservationRow row : batch.rows()) {
            if (row.vehicleId() != null) {
                rowsByVehicle.merge(row.vehicleId(), 1, Integer::sum);
            }
        }
        List<AlightingAwareObservation> observations = new ArrayList<>();
        for (ForecastObservationRow row : batch.rows()) {
            if (row.vehicleId() == null || rowsByVehicle.get(row.vehicleId()) != 1) {
                continue;
            }
            observations.add(new AlightingAwareObservation(row.observationId(), batch.observationBatchId(), rank,
                batch.confirmedAt(), row.vehicleId(), row.passedStopOrder(), row.stopOrder(), row.runningState(),
                knownSeats(row), row.crowdLevel(), batch.routeVersionId(), batch.receivedAt(),
                isDown(row.stopOrder(), row.runningState(), turn)));
        }
        return observations;
    }

    private static Integer knownSeats(ForecastObservationRow row) {
        Integer seats = row.remainingSeats();
        if (isSeatUnknown(seats, row)) {
            return null;
        }
        return seats;
    }

    private static boolean isSeatUnknown(Integer seats, ForecastObservationRow row) {
        return seats == null || seats < 0 || seats > MAXIMUM_SEATS || row.seatUnknownReason() != null;
    }

    private static boolean isDown(final int stop, final int state, final int turn) {
        return stop > turn || (stop == turn && state == 2) || (stop == 1 && state != 2);
    }

    private static boolean isEligibleSource(AlightingAwareObservation source, final long batchId) {
        return source.batchId() == batchId && source.hasVehicleIdentity() && source.seats() != null;
    }

    private static ForecastStageResult.WithheldVehicle withheld(AlightingAwareObservation source, String reason) {
        return new ForecastStageResult.WithheldVehicle(source.observationId(), source.vehicleId(), reason);
    }

    /**
     * 통과의 도착 전 관측부터 닫는 관측까지 같은 차량의 관측은 한 연결에 속한다.
     * 그 가운데 지난 정류장이 목표와 같고 좌석을 아는 가장 늦은 관측의 확정 시각이다.
     */
    private static Instant reachedKnownSeatAt(AlightingAwarePassEvent event, List<AlightingAwareObservation> history) {
        Instant from = event.arrival().receivedAt();
        Instant until = event.confirmation().receivedAt();
        Instant latest = null;
        for (AlightingAwareObservation observation : history) {
            if (lacksKnownSeatAtStop(observation, event)) {
                continue;
            }
            Instant at = observation.receivedAt();
            if (at.isBefore(from) || at.isAfter(until)) {
                continue;
            }
            if (latest == null || at.isAfter(latest)) {
                latest = at;
            }
        }
        return latest;
    }

    private static boolean lacksKnownSeatAtStop(AlightingAwareObservation observation, AlightingAwarePassEvent event) {
        return !observation.vehicleId().equals(event.vehicleId())
            || observation.passedStopOrder() != event.stopOrder()
            || observation.seats() == null;
    }

    private static void requireSameRoute(RouteForecastState state, AlightingAwareRouteStatistics statistics) {
        if (!statistics.confirmedProfile()) {
            throw new IllegalArgumentException("예보 단계는 확정 판본 통계만 받는다");
        }
        if (!state.route().equals(statistics.route())) {
            throw new IllegalArgumentException("노선 상태와 통계의 노선이 다르다");
        }
    }

    /** 배치 하나를 반영하면서 무엇까지 만들지다. */
    private enum Mode {
        /** 예보 입력과 닫힌 통과를 만든다. */
        FORECAST,
        /** 상태만 전진시킨다. */
        REPLAY,
        /** 상태를 전진시키고 닫힌 통과만 만든다. */
        REPLAY_CLOSING
    }
}
