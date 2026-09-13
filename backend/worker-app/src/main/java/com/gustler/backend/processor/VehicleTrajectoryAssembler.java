package com.gustler.backend.processor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 관측 이력에서 차량마다 궤적 재료 셋을 만든다.
 *
 * <p>규칙보다 결손이 비싸서 끊긴 자리를 판정하는 일이 전부 여기 모인다.
 *
 * <p>여정 키는 양쪽에 다 있을 때만 경계로 쓴다. 지금은 적재가 그 열을 안 채워 늘 비어 있어서,
 * 대신 같은 차량과 같은 노선 판본으로 잇고 판 결손 · 순번 건너뜀 · 순번 역행으로 막는다.
 * 순번은 회차해 돌아올 때까지 늘기만 해서, 되돌아간다는 것은 차가 새 여정을 시작했다는 뜻이다.
 * 여정 키가 채워지면 이 판정이 저절로 더 조여진다.
 */
public final class VehicleTrajectoryAssembler {

    private static final int SAME_STOP = 0;
    private static final int NEXT_STOP = 1;

    private VehicleTrajectoryAssembler() {
    }

    /**
     * 차량마다 궤적 재료를 만든다.
     *
     * <p>보여 준 최대 잔여석만 30분 창 밖에서 온다. 이력 전부를 봐야 나오는 값이라 여기서
     * 세지 않고 차량 아이디로 받는다.
     *
     * <p><b>그 목록에 없는 차량은 궤적을 안 만든다.</b> 잔여석을 한 번도 안 보여 준 차량과 줄곧
     * 만석이던 차량이 그 자리다. 정원을 모르는데 1석으로 꾸며 내면 지금 잔여석 비율이 늘 0 이고
     * 정원 비율이 1/68 이라, 값은 나오는데 뜻이 없는 예보가 나간다. 예보를 안 내는 편이 낫다.
     */
    public static List<VehicleTrajectory> assemble(
        ObservationHistory history,
        Map<String, Integer> maximumSeatsByVehicle
    ) {
        List<VehicleTrajectory> trajectories = new ArrayList<>();
        for (TrajectoryObservation observation : history.targetBatch().observations()) {
            if (!maximumSeatsByVehicle.containsKey(observation.vehicleId())) {
                continue;
            }
            trajectories.add(trajectoryOf(history, observation, maximumSeatsByVehicle));
        }
        return List.copyOf(trajectories);
    }

    private static VehicleTrajectory trajectoryOf(
        ObservationHistory history,
        TrajectoryObservation target,
        Map<String, Integer> maximumSeatsByVehicle
    ) {
        LinkedChain chain = chainOf(history, target);
        return new VehicleTrajectory(
            target.vehicleObservationId(),
            target.vehicle(),
            target.seats(),
            slopeOf(chain),
            findPrecedingVehicle(history, target),
            streakOf(chain),
            maximumSeatsByVehicle.get(target.vehicleId()));
    }

    /**
     * 대상 관측에서 판을 하나씩 거슬러 올라가며 이어지는 관측을 모은다. 최신이 앞이다.
     * 끊긴 자리에서 멈추고 왜 멈췄는지를 같이 든다.
     */
    private static LinkedChain chainOf(
        ObservationHistory history,
        TrajectoryObservation target
    ) {
        List<ObservedBatch> batches = history.batches();
        List<TrajectoryObservation> linked = new ArrayList<>();
        linked.add(target);

        TrajectoryObservation later = target;
        for (int index = batches.size() - 2; index >= 0; index--) {
            Optional<TrajectoryObservation> earlier = batches.get(index).observationOf(later.vehicleId());
            if (earlier.isEmpty()) {
                return new LinkedChain(linked, TrajectoryGap.OBSERVATION_BATCH_MISSING);
            }
            Optional<TrajectoryGap> gap = gapBetween(earlier.get(), later);
            if (gap.isPresent()) {
                return new LinkedChain(linked, gap.get());
            }
            linked.add(earlier.get());
            later = earlier.get();
        }
        return new LinkedChain(linked, TrajectoryGap.NO_EARLIER_OBSERVATION);
    }

    /** 두 관측을 이어도 되나. 이으면 안 되는 사유가 있으면 그것을 준다. */
    private static Optional<TrajectoryGap> gapBetween(
        TrajectoryObservation earlier,
        TrajectoryObservation later
    ) {
        if (isTripChanged(earlier, later)) {
            return Optional.of(TrajectoryGap.TRIP_KEY_CHANGED);
        }
        final int stopsMoved = later.passedStopOrder() - earlier.passedStopOrder();
        if (stopsMoved < SAME_STOP) {
            return Optional.of(TrajectoryGap.STOP_ORDER_WENT_BACK);
        }
        if (stopsMoved > NEXT_STOP) {
            return Optional.of(TrajectoryGap.STOP_ORDER_JUMPED);
        }
        return Optional.empty();
    }

    /** 여정 키는 양쪽에 다 있을 때만 경계가 된다. 비어 있으면 여정을 모를 뿐이라 끊지 않는다. */
    private static boolean isTripChanged(
        TrajectoryObservation earlier,
        TrajectoryObservation later
    ) {
        return earlier.vehicleTripKey() != null
            && later.vehicleTripKey() != null
            && !earlier.vehicleTripKey().equals(later.vehicleTripKey());
    }

    private static SeatSlope slopeOf(
        LinkedChain chain
    ) {
        if (!chain.hasEarlierObservation()) {
            return new SeatSlope.Unknown(chain.gap());
        }
        if (!(chain.latest().seats() instanceof ObservedSeats.Known later)
            || !(chain.justBeforeLatest().seats() instanceof ObservedSeats.Known earlier)) {
            return new SeatSlope.Unknown(TrajectoryGap.SEATS_UNKNOWN);
        }
        return new SeatSlope.Known(later.seats() - earlier.seats());
    }

    /**
     * 이어진 관측을 최신부터 훑으며 만석이 몇 정류장째인지 센다.
     * 같은 정류소를 여러 판에서 봐도 정류장 하나로 센다.
     */
    private static FullSeatStreak streakOf(
        LinkedChain chain
    ) {
        int stopCount = 0;
        int countedStopOrder = 0;
        boolean hasCounted = false;

        for (TrajectoryObservation observation : chain.observations()) {
            if (observation.seats() instanceof ObservedSeats.Unknown) {
                return new FullSeatStreak.CutByGap(stopCount, TrajectoryGap.SEATS_UNKNOWN);
            }
            if (!observation.isFull()) {
                return new FullSeatStreak.SeenToEnd(stopCount);
            }
            if (!hasCounted || countedStopOrder != observation.passedStopOrder()) {
                stopCount++;
                countedStopOrder = observation.passedStopOrder();
                hasCounted = true;
            }
        }
        return new FullSeatStreak.CutByGap(stopCount, chain.gap());
    }

    /**
     * 같은 정류소를 나보다 앞서 지난 차. 그 순번에 처음 들어온 시각으로 앞뒤를 가리고,
     * 나보다 먼저 들어온 차 중 가장 늦게 들어온 차를 고른다.
     *
     * <p>한 판 안에서는 앞뒤를 못 가린다. 그 판의 차는 관측 시각이 다 같고, 상류가 준 행 순서는
     * 통과 순서가 아니다. 그래서 나와 같은 판으로 그 순번에 들어온 차가 있으면 모른다고 답한다.
     * 그렇게 해야 두 차가 서로를 앞차로 지목하는 일이 안 생긴다.
     */
    private static PrecedingVehicle findPrecedingVehicle(
        ObservationHistory history,
        TrajectoryObservation target
    ) {
        Map<String, StopEntry> entryByVehicle = stopEntriesAt(history, target.passedStopOrder());
        Instant enteredAt = entryByVehicle.get(target.vehicleId()).enteredAt();

        StopEntry nearestAhead = null;
        for (StopEntry other : entryByVehicle.values()) {
            if (other.vehicleId().equals(target.vehicleId())) {
                continue;
            }
            if (other.enteredAt().equals(enteredAt)) {
                return new PrecedingVehicle.Unknown(TrajectoryGap.ARRIVAL_ORDER_UNKNOWN);
            }
            if (other.enteredAt().isBefore(enteredAt) && isLaterThan(other, nearestAhead)) {
                nearestAhead = other;
            }
        }
        if (nearestAhead == null) {
            return new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD);
        }
        return toPrecedingVehicle(nearestAhead);
    }

    /**
     * 차량마다 그 순번에 처음 들어온 판을 찾는다. 판이 오래된 것부터 늘어서 있어서
     * 처음 넣은 것이 곧 처음 들어온 시점이다.
     */
    private static Map<String, StopEntry> stopEntriesAt(
        ObservationHistory history,
        final int passedStopOrder
    ) {
        Map<String, StopEntry> entryByVehicle = new LinkedHashMap<>();
        for (ObservedBatch batch : history.batches()) {
            for (TrajectoryObservation observation : batch.observations()) {
                if (observation.passedStopOrder() == passedStopOrder) {
                    entryByVehicle.putIfAbsent(
                        observation.vehicleId(),
                        new StopEntry(observation.vehicleId(), batch.responseReceivedAt(), observation));
                }
            }
        }
        return entryByVehicle;
    }

    private static boolean isLaterThan(
        StopEntry candidate,
        StopEntry chosen
    ) {
        return chosen == null || candidate.enteredAt().isAfter(chosen.enteredAt());
    }

    private static PrecedingVehicle toPrecedingVehicle(
        StopEntry ahead
    ) {
        if (!(ahead.observation().seats() instanceof ObservedSeats.Known known)) {
            return new PrecedingVehicle.Unknown(TrajectoryGap.SEATS_UNKNOWN);
        }
        return new PrecedingVehicle.Known(ahead.vehicleId(), known.seats(), ahead.enteredAt());
    }

    /** 차량 하나가 어느 순번에 처음 들어온 시점과 그때의 관측. */
    private record StopEntry(
        String vehicleId,
        Instant enteredAt,
        TrajectoryObservation observation
    ) {
    }

    /** 대상 관측에서 뒤로 이어진 관측들. 최신이 앞이고, gap 은 이어지기를 멈춘 사유다. */
    private record LinkedChain(
        List<TrajectoryObservation> observations,
        TrajectoryGap gap
    ) {

        private static final int LATEST = 0;
        private static final int JUST_BEFORE_LATEST = 1;

        boolean hasEarlierObservation() {
            return observations.size() > JUST_BEFORE_LATEST;
        }

        TrajectoryObservation latest() {
            return observations.get(LATEST);
        }

        TrajectoryObservation justBeforeLatest() {
            return observations.get(JUST_BEFORE_LATEST);
        }
    }
}
