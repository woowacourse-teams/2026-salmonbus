package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareVehiclePrefix;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 노선 하나(같은 표시 이름의 호환 판본 전체)의 예보 이력 상태다. 불변이며, 전진은 {@link ForecastStage}가 새 객체로 돌려준다.
 *
 * <p>차량별 누적 상태·보존 창의 관측·보존 창의 통과 사건·마지막으로 반영한 배치는 옛 경로가 체크포인트 파일에 두는 것과
 * 같은 내용이다.
 * 여기에 차량별 추적({@link VehicleTrack})을 더 든다. 추적은 차량의 마지막 관측, 지금 연결의 시작 관측 번호,
 * 연결 시작과 운행 시작을 실제로 보았는지를 담는다. 연결의 시작은 {@code AlightingAwareHistoryCompactor}와 같은
 * {@link AlightingAwareObservation#startsConfirmedChainAfter} 판정으로 정한다.
 *
 * <p>검증된 출발 지점부터 이어 온 상태는 모든 차량의 운행 시작을 안다. 최근 관측만 되감아 만든 상태는 창 시작 시각을 들고,
 * 창 안에서 운행 시작이 보이지 않은 차량과, 창 밖 관측이 아직 입력에 영향을 줄 수 있는 동안의 배치를 예보에서 뺀다.
 * 판정 기준은 {@link #tripStartKnown(String)}과 {@link #historyCompleteAt(Instant)}에 적었다.
 */
public final class RouteForecastState {
    private static final long CHAIN_LINK_SECONDS = 90;
    private static final long TRIP_LINK_SECONDS = 1800;
    private static final long HISTORY_INFLUENCE_SECONDS = 5400;

    private final String route;
    private final Instant replayFrom;
    private final Map<String, AlightingAwareVehiclePrefix> prefix;
    private final List<AlightingAwareObservation> observations;
    private final List<AlightingAwarePassEvent> events;
    private final Progress progress;
    private final Cursor cursor;

    private RouteForecastState(
        String route,
        Instant replayFrom,
        Map<String, AlightingAwareVehiclePrefix> prefix,
        List<AlightingAwareObservation> observations,
        List<AlightingAwarePassEvent> events,
        Progress progress,
        Cursor cursor
    ) {
        this.route = Objects.requireNonNull(route);
        this.replayFrom = replayFrom;
        this.prefix = Map.copyOf(prefix);
        this.observations = List.copyOf(observations);
        this.events = List.copyOf(events);
        this.progress = progress;
        this.cursor = cursor;
    }

    /** 검증된 출발 지점에서 시작하는 빈 상태다. 처음 보는 차량의 첫 관측을 운행 시작으로 인정한다. */
    public static RouteForecastState fromVerifiedOrigin(String route) {
        return new RouteForecastState(route, null, Map.of(), List.of(), List.of(),
            new Progress(Map.of(), Map.of(), null, false), null);
    }

    /**
     * {@code from}부터 확정된 배치만 되감아 만들 빈 상태다. {@code from} 앞의 관측은 없는 것으로 보고,
     * 그 때문에 입력이 달라질 수 있는 차량과 배치는 예보에서 뺀다.
     */
    public static RouteForecastState replayingFrom(String route, Instant from) {
        Objects.requireNonNull(from);
        return new RouteForecastState(route, from, Map.of(), List.of(), List.of(),
            new Progress(Map.of(), Map.of(), from.plusSeconds(HISTORY_INFLUENCE_SECONDS), true), null);
    }

    /** 노선 표시 이름이다. */
    public String route() {
        return route;
    }

    /** 되감기 창의 시작 시각이다. 검증된 출발 지점에서 이어 온 상태는 비어 있다. */
    public Optional<Instant> replayFrom() {
        return Optional.ofNullable(replayFrom);
    }

    /** 보존 창 밖으로 접은 차량별 누적 상태다. */
    public Map<String, AlightingAwareVehiclePrefix> prefix() {
        return prefix;
    }

    /** 보존 창의 관측이다. (확정 시각, 배치 번호, 차량 번호) 순서다. */
    public List<AlightingAwareObservation> observations() {
        return observations;
    }

    /** 보존 창의 통과 사건이다. */
    public List<AlightingAwarePassEvent> events() {
        return events;
    }

    /** 이 상태가 관측한 모든 차량의 추적이다. */
    public Map<String, VehicleTrack> vehicles() {
        return progress.vehicles;
    }

    /** 마지막으로 반영한 확정 배치다. 아직 없으면 비어 있다. */
    public Optional<Cursor> cursor() {
        return Optional.ofNullable(cursor);
    }

    /** 다음 확정 배치가 받을 순위다. 확정 조건을 만족한 배치만 순위를 쓴다. */
    public long nextRank() {
        if (cursor == null) {
            return 0;
        }
        return cursor.rank() + 1;
    }

    /** 차량의 지금 운행이 시작된 관측을 이 상태가 보았는지다. 처음 보는 차량은 거짓이다. */
    public boolean tripStartKnown(String vehicleId) {
        return progress.tripStartKnown(vehicleId);
    }

    /**
     * {@code at} 시각의 예보 입력이 창 밖 관측과 무관한지다. 검증된 출발 지점에서 이어 온 상태는 항상 참이다.
     * 되감은 상태는 창 시작 뒤 90분이 지났고, 창 시작에 걸친 연결이 놓쳤을 수 있는 통과의 영향이 끝났을 때 참이다.
     * 통과 하나는 닫힌 시각부터 90분(양 끝 포함) 동안 정류장 대기 상태와 최근 한 시간 편차로 다른 차량의 입력에 들어간다.
     */
    public boolean historyCompleteAt(Instant at) {
        return progress.historyCompleteAt(at);
    }

    /**
     * 되감은 창이 {@code at} 시각의 예보에 모자란 정도다. 검증된 출발 지점에서 이어 온 상태는 모자람이 없다.
     * 운행 시작을 보지 못한 차량은 마지막 관측이 {@code at}부터 30분 안인 것만 센다. 그보다 오래 보이지 않은 차량은 다음 관측에서
     * 새 운행을 시작하므로 창을 늘려도 달라지지 않는다.
     */
    public ReplayShortfall replayShortfallAt(Instant at) {
        if (replayFrom == null) {
            return ReplayShortfall.NONE;
        }
        Instant recent = at.minusSeconds(TRIP_LINK_SECONDS);
        final int unknown = (int) progress.vehicles.values().stream()
            .filter(track -> !track.tripStartKnown())
            .filter(track -> !track.last().receivedAt().isBefore(recent))
            .count();
        return new ReplayShortfall(!progress.historyCompleteAt(at), unknown);
    }

    boolean precedes(ForecastBatch batch) {
        if (cursor == null) {
            return true;
        }
        if (batch.confirmedAt().isAfter(cursor.confirmedAt())) {
            return true;
        }
        return batch.confirmedAt().equals(cursor.confirmedAt())
            && batch.observationBatchId() > cursor.observationBatchId();
    }

    /**
     * 마지막으로 반영한 배치보다 먼저 수신한 배치인지다. 이런 배치를 반영하면 이력의 순서가 수신 순서와 달라진다.
     * 반영한 배치가 없으면 거짓이다.
     */
    boolean receivedBeforeApplied(ForecastBatch batch) {
        if (cursor == null) {
            return false;
        }
        return batch.receivedAt().isBefore(cursor.receivedAt());
    }

    /** 이 상태 위에 한 배치의 관측을 차례로 반영한 차량 추적이다. 상태 자체는 바꾸지 않는다. */
    Progress track(List<AlightingAwareObservation> arrivals, List<Integer> realStopOrders) {
        Map<String, VehicleTrack> tracks = new HashMap<>(progress.vehicles);
        Map<String, Integer> pending = new HashMap<>(progress.truncatedPasses);
        Instant incompleteThrough = progress.historyIncompleteThrough;
        for (AlightingAwareObservation observation : arrivals) {
            VehicleTrack track = tracks.get(observation.vehicleId());
            if (track == null) {
                VehicleTrack first = firstTrack(observation);
                Integer resolution = truncatedPassResolution(observation.passedStopOrder(), realStopOrders);
                if (!first.chainStartKnown() && resolution != null) {
                    pending.put(observation.vehicleId(), resolution);
                }
                tracks.put(observation.vehicleId(), first);
                continue;
            }
            if (observation.startsConfirmedChainAfter(track.last())) {
                pending.remove(observation.vehicleId());
                VehicleTrack restarted = new VehicleTrack(observation, observation.observationId(), true,
                    track.tripStartKnown() || observation.startsConfirmedTripAfter(track.last()));
                tracks.put(observation.vehicleId(), restarted);
                continue;
            }
            Integer resolution = pending.get(observation.vehicleId());
            if (resolution != null && observation.passedStopOrder() >= resolution) {
                pending.remove(observation.vehicleId());
                incompleteThrough = later(incompleteThrough,
                    observation.receivedAt().plusSeconds(HISTORY_INFLUENCE_SECONDS));
            }
            VehicleTrack continued = new VehicleTrack(observation, track.chainStartObservationId(),
                track.chainStartKnown(), track.tripStartKnown());
            tracks.put(observation.vehicleId(), continued);
        }
        return new Progress(tracks, pending, incompleteThrough, progress.replayed);
    }

    RouteForecastState next(
        Progress progress,
        Map<String, AlightingAwareVehiclePrefix> nextPrefix,
        List<AlightingAwareObservation> nextObservations,
        List<AlightingAwarePassEvent> nextEvents,
        Cursor nextCursor
    ) {
        return new RouteForecastState(route, replayFrom, nextPrefix, nextObservations, nextEvents, progress,
            nextCursor);
    }

    private VehicleTrack firstTrack(AlightingAwareObservation observation) {
        return new VehicleTrack(observation, observation.observationId(),
            isAtLeastAfterWindowStart(observation, CHAIN_LINK_SECONDS),
            isAtLeastAfterWindowStart(observation, TRIP_LINK_SECONDS));
    }

    /**
     * 창 앞의 마지막 관측은 창 시작보다 이르므로, 창 시작부터 {@code seconds} 이상 지난 첫 관측은
     * 앞 관측과의 확정 시각 간격이 {@code seconds}를 넘어 반드시 새 연결(90초)이나 새 운행(30분)을 시작한다.
     */
    private boolean isAtLeastAfterWindowStart(AlightingAwareObservation observation, final long seconds) {
        if (replayFrom == null) {
            return true;
        }
        return !observation.receivedAt().isBefore(replayFrom.plusSeconds(seconds));
    }

    /**
     * 창 시작에 걸친 연결은 창 앞에 도착 전 관측이 있던 통과 하나를 놓칠 수 있다.
     * 첫 관측이 실정류장 구간 [목표, 다음 실정류장) 안이면 그 목표가 놓칠 수 있는 통과이고, 다음 실정류장에 닿으면 닫힌다.
     * 확정 판본은 첫 실정류장을 목표로 쓰지 않고, 마지막 실정류장은 다음이 없어 통과가 생기지 않는다.
     */
    private static Integer truncatedPassResolution(final int passedStopOrder, List<Integer> realStopOrders) {
        List<Integer> stops = realStopOrders.stream().distinct().sorted().toList();
        for (int index = 1; index + 1 < stops.size(); index++) {
            if (passedStopOrder >= stops.get(index) && passedStopOrder < stops.get(index + 1)) {
                return stops.get(index + 1);
            }
        }
        return null;
    }

    private static Instant later(Instant current, Instant candidate) {
        if (current == null || candidate.isAfter(current)) {
            return candidate;
        }
        return current;
    }

    /** 마지막으로 반영한 확정 배치의 번호·순위·확정 시각·수신 시각이다. */
    public record Cursor(long observationBatchId, long rank, Instant confirmedAt, Instant receivedAt) {
    }

    /**
     * 되감은 창이 모자란 정도다. 이력이 미완이면 노선 전체가, 운행 시작을 보지 못한 차량은 그 차량만 예보에서 빠진다.
     *
     * @param historyIncomplete 창 밖 관측이 아직 입력에 영향을 줄 수 있으면 참
     * @param vehiclesWithoutTripStart 최근에 보인 차량 가운데 운행 시작을 보지 못한 차량 수
     */
    public record ReplayShortfall(boolean historyIncomplete, int vehiclesWithoutTripStart) {
        static final ReplayShortfall NONE = new ReplayShortfall(false, 0);

        /** 예보에서 빠지는 것이 없으면 참이다. */
        public boolean none() {
            return !historyIncomplete && vehiclesWithoutTripStart == 0;
        }

        /** 이쪽이 {@code other}보다 덜 모자라면 참이다. 노선 전체가 빠지는 쪽을 더 모자란 것으로 본다. */
        boolean lessThan(ReplayShortfall other) {
            if (historyIncomplete != other.historyIncomplete) {
                return other.historyIncomplete;
            }
            return vehiclesWithoutTripStart < other.vehiclesWithoutTripStart;
        }
    }

    /**
     * 차량 하나의 추적이다. 마지막 관측과 지금 연결의 시작 관측 번호, 그리고 연결 시작과 운행 시작을 실제로 보았는지를 담는다.
     * 연결 시작을 보지 못했으면 시작 관측 번호는 창 안 첫 관측이며 실제 시작이 아닐 수 있다.
     */
    public record VehicleTrack(
        AlightingAwareObservation last,
        long chainStartObservationId,
        boolean chainStartKnown,
        boolean tripStartKnown
    ) {
    }

    /** 한 배치를 반영한 차량 추적과 되감기 영향 범위다. 상태를 만들기 전 단계의 값이다. */
    static final class Progress {
        private final Map<String, VehicleTrack> vehicles;
        private final Map<String, Integer> truncatedPasses;
        private final Instant historyIncompleteThrough;
        private final boolean replayed;

        private Progress(
            Map<String, VehicleTrack> vehicles,
            Map<String, Integer> truncatedPasses,
            Instant historyIncompleteThrough,
            final boolean replayed
        ) {
            this.vehicles = Map.copyOf(vehicles);
            this.truncatedPasses = Map.copyOf(truncatedPasses);
            this.historyIncompleteThrough = historyIncompleteThrough;
            this.replayed = replayed;
        }

        VehicleTrack vehicle(String vehicleId) {
            return vehicles.get(vehicleId);
        }

        boolean tripStartKnown(String vehicleId) {
            VehicleTrack track = vehicles.get(vehicleId);
            return track != null && track.tripStartKnown();
        }

        boolean historyCompleteAt(Instant at) {
            if (!replayed) {
                return true;
            }
            return truncatedPasses.isEmpty() && at.isAfter(historyIncompleteThrough);
        }
    }
}
