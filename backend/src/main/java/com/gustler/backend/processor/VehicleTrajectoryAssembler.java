package com.gustler.backend.processor;

import java.util.ArrayList;
import java.util.List;
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

    public static List<VehicleTrajectory> assemble(
        ObservationHistory history
    ) {
        List<VehicleTrajectory> trajectories = new ArrayList<>();
        for (TrajectoryObservation observation : history.targetBatch().observations()) {
            trajectories.add(trajectoryOf(history, observation));
        }
        return List.copyOf(trajectories);
    }

    private static VehicleTrajectory trajectoryOf(
        ObservationHistory history,
        TrajectoryObservation target
    ) {
        LinkedChain chain = chainOf(history, target);
        return new VehicleTrajectory(
            target.vehicle(),
            slopeOf(chain),
            findPrecedingVehicle(history, target),
            streakOf(chain));
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
        ObservedVehicle later = chain.latest().vehicle();
        ObservedVehicle earlier = chain.justBeforeLatest().vehicle();
        if (!later.hasKnownSeats() || !earlier.hasKnownSeats()) {
            return new SeatSlope.Unknown(TrajectoryGap.SEATS_UNKNOWN);
        }
        return new SeatSlope.Known(later.remainingSeats() - earlier.remainingSeats());
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
            if (!observation.vehicle().hasKnownSeats()) {
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
     * 같은 정류소를 나보다 앞서 지난 다른 차를 이력에서 찾는다. 가장 최근에 지난 차를 고른다.
     * 여정이 아니라 정류소로 찾는 것이라 궤적이 끊겨도 상관없다.
     */
    private static PrecedingVehicle findPrecedingVehicle(
        ObservationHistory history,
        TrajectoryObservation target
    ) {
        List<ObservedBatch> batches = history.batches();
        for (int index = batches.size() - 2; index >= 0; index--) {
            Optional<TrajectoryObservation> ahead = batches.get(index).observations().stream()
                .filter(observation -> !observation.vehicleId().equals(target.vehicleId()))
                .filter(observation -> observation.passedStopOrder() == target.passedStopOrder())
                .findFirst();
            if (ahead.isPresent()) {
                return toPrecedingVehicle(ahead.get());
            }
        }
        return new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD);
    }

    private static PrecedingVehicle toPrecedingVehicle(
        TrajectoryObservation ahead
    ) {
        if (!ahead.vehicle().hasKnownSeats()) {
            return new PrecedingVehicle.Unknown(TrajectoryGap.SEATS_UNKNOWN);
        }
        return new PrecedingVehicle.Known(
            ahead.vehicleId(),
            ahead.vehicle().remainingSeats(),
            ahead.vehicle().observedAt());
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
