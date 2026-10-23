package com.gustler.backend.forecasting.domain.publication;

import com.gustler.backend.forecasting.domain.model.FullSeatStreak;
import com.gustler.backend.forecasting.domain.model.FullSeatStreak.CutByGap;
import com.gustler.backend.forecasting.domain.model.FullSeatStreak.SeenToEnd;
import com.gustler.backend.forecasting.domain.model.ObservedSeats;
import com.gustler.backend.forecasting.domain.model.ObservedSeats.Known;
import com.gustler.backend.forecasting.domain.model.PrecedingVehicle;
import com.gustler.backend.forecasting.domain.model.SeatSlope;
import com.gustler.backend.forecasting.domain.model.SeatSlope.Unknown;
import com.gustler.backend.forecasting.domain.model.TrajectoryGap;
import com.gustler.backend.forecasting.domain.model.VehicleTrajectory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 관측 이력에서 차량마다 궤적 입력 셋을 만든다.
 *
 * <p>관측 결손에 따른 연결 중단을 일관되게 판정하도록 관련 규칙을 이곳에 모은다.
 *
 * <p>조회 어댑터가 vehicle_trip_key에 저장한 편도 ID를 전달한다. 편도가 다르면 연결을 끊고, 같은 편도 안에서도
 * 수집 배치 결손·순번 건너뜀·순번 역행으로 특징 계산을 중단한다.
 */
public final class VehicleTrajectoryAssembler {
    private static final int SAME_STOP = 0;
    private static final int NEXT_STOP = 1;

    private VehicleTrajectoryAssembler() {
    }

    /**
     * 차량마다 궤적 입력을 만든다.
     *
     * <p>관측한 최대 잔여석만 30분 창 밖의 전체 이력에서 얻는다. 여기서 계산하지 않고 차량 아이디별로 받는다.
     *
     * <p><b>그 목록에 없는 차량은 궤적을 만들지 않는다.</b> 잔여석을 한 번도 관측하지 못했거나 줄곧
     * 만석이던 차량이 해당한다. 정원을 모르는데 1석으로 임의 설정하면 현재 잔여석 비율이 항상 0 이고
     * 정원 비율은 1/68 이 된다. 계산값에 의미가 없으므로 예보를 발행하지 않는 편이 낫다.
     */
    public static List<VehicleTrajectory> assemble(
        ObservationHistory history,
        Map<String, Integer> maximumSeatsByVehicle
    ) {
        List<VehicleTrajectory> trajectories = history.targetBatch()
            .observations()
            .stream()
            .filter(observation -> maximumSeatsByVehicle.containsKey(observation.vehicleId()))
            .map(observation -> trajectoryOf(history, observation, maximumSeatsByVehicle))
            .collect(Collectors.toCollection(ArrayList::new));
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
     * 대상 관측부터 수집 배치를 역순으로 읽어 연속된 관측을 모은다. 최신 관측이 앞에 온다.
     * 연결이 끊기면 중단하고 그 사유를 함께 기록한다.
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
            ObservedBatch batch = batches.get(index);
            Optional<TrajectoryObservation> earlier = batch.observationOf(later.vehicleId());
            if (earlier.isEmpty()) {
                return new LinkedChain(linked, TrajectoryGap.OBSERVATION_BATCH_MISSING);
            }
            TrajectoryObservation earlierObservation = earlier.get();
            Optional<TrajectoryGap> gap = gapBetween(earlierObservation, later);
            if (gap.isPresent()) {
                return new LinkedChain(linked, gap.get());
            }
            linked.add(earlierObservation);
            later = earlierObservation;
        }
        return new LinkedChain(linked, TrajectoryGap.NO_EARLIER_OBSERVATION);
    }

    /** 두 관측의 연결 가능 여부를 확인하고 연결할 수 없으면 사유를 반환한다. */
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
        String earlierTripKey = earlier.vehicleTripKey();
        String laterTripKey = later.vehicleTripKey();
        if (earlierTripKey == null || laterTripKey == null) {
            return false;
        }
        return !earlierTripKey.equals(laterTripKey);
    }

    private static SeatSlope slopeOf(
        LinkedChain chain
    ) {
        if (!chain.hasEarlierObservation()) {
            return new Unknown(chain.gap());
        }
        TrajectoryObservation latest = chain.latest();
        TrajectoryObservation justBeforeLatest = chain.justBeforeLatest();
        if (!(latest.seats() instanceof Known later)
                || !(justBeforeLatest.seats() instanceof Known earlier)) {
            return new Unknown(TrajectoryGap.SEATS_UNKNOWN);
        }
        return new SeatSlope.Known(later.seats() - earlier.seats());
    }

    /**
     * 이어진 관측을 최신부터 훑으며 만석이 몇 정류장째인지 센다.
     * 같은 정류소가 여러 수집 배치에 나타나도 정류장 하나로 센다.
     */
    private static FullSeatStreak streakOf(
        LinkedChain chain
    ) {
        int stopCount = 0;
        int countedStopOrder = 0;
        boolean hasCounted = false;

        for (TrajectoryObservation observation : chain.observations()) {
            if (observation.seats() instanceof ObservedSeats.Unknown) {
                return new CutByGap(stopCount, TrajectoryGap.SEATS_UNKNOWN);
            }
            if (!observation.isFull()) {
                return new SeenToEnd(stopCount);
            }
            if (!hasCounted || countedStopOrder != observation.passedStopOrder()) {
                stopCount++;
                countedStopOrder = observation.passedStopOrder();
                hasCounted = true;
            }
        }
        return new CutByGap(stopCount, chain.gap());
    }

    /**
     * 같은 정류소를 대상 차량보다 먼저 지난 차량을 찾는다. 해당 순번에 처음 진입한 시각으로 순서를 비교하고,
     * 대상 차량보다 먼저 진입한 차량 중 가장 늦게 진입한 차량을 고른다.
     *
     * <p>같은 수집 배치의 차량은 관측 시각이 같아 통과 순서를 구분할 수 없다.
     * 외부 API의 행 순서도 통과 순서를 뜻하지 않는다. 같은 배치에서 해당 순번에 진입한 차량이 있으면
     * 앞차를 알 수 없는 것으로 처리해 두 차량이 서로를 앞차로 선택하지 않게 한다.
     */
    private static PrecedingVehicle findPrecedingVehicle(
        ObservationHistory history,
        TrajectoryObservation target
    ) {
        Map<String, StopEntry> entryByVehicle = stopEntriesAt(history, target.passedStopOrder());
        StopEntry targetEntry = entryByVehicle.get(target.vehicleId());
        Instant enteredAt = targetEntry.enteredAt();

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
     * 차량마다 해당 순번에 처음 진입한 수집 배치를 찾는다. 배치가 시각 오름차순으로 정렬돼 있으므로
     * 처음 기록한 관측이 최초 진입 시점이다.
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
        if (!(ahead.observation().seats() instanceof Known known)) {
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

    /** 대상 관측에서 뒤로 이어진 관측들. 최신이 앞이며 gap 은 연결을 중단한 사유다. */
    private record LinkedChain(
        List<TrajectoryObservation> observations,
        TrajectoryGap gap
    ) {
        private static final int LATEST = 0;

        private static final int JUST_BEFORE_LATEST = 1;

        /** 호출자가 가진 원본과 분리해 null·원소·순서를 보존한다. */
        private LinkedChain {
            if (observations != null) {
                observations = Collections.unmodifiableList(new ArrayList<>(observations));
            }
        }

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
