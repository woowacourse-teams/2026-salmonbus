package com.gustler.backend.processor;

import java.util.Objects;

/**
 * 좌석 예보 하나를 계산하는 데 드는 재료 전부.
 *
 * <p><b>모델은 DB 를 직접 읽지 않는다.</b> 재료를 통째로 받아 계산만 한다. 그래서 백테스트할 때
 * 과거 시점 재료를 손으로 만들어 넣으면 그대로 돈다. 이 성질이 깨지면 백테스트가 불가능해진다.
 *
 * <p>인자를 넷으로 늘리지 않고 값 하나로 묶은 이유도 같다. 부르는 자리가 늘 넷을 챙기면 재료가
 * 하나 더 늘 때마다 모델 계약이 바뀌고 백테스트 코드가 전부 따라 바뀐다. 값 하나면 그 값만 자란다.
 *
 * <p>넷이 서로 다른 노선 판본의 것이면 여기서 멈춘다. 설계행렬은 넷을 한 줄에 섞어 놓기 때문에
 * 어긋난 채로 지나가면 그럴듯한 수가 나오고 아무도 못 알아챈다.
 *
 * <p><b>시간대도 하나만 든다.</b> 설계행렬의 아침·저녁 열과 셀 통계가 서로 다른 시간대를 쓰면
 * 한 예보 행이 두 시간대의 값을 섞는다. 셀 통계가 자기 시간대를 들고 있어서 여기서 대조한다.
 */
public record SeatForecastInput(
    VehicleStopTarget target,
    VehicleTrajectory trajectory,
    StopDemandStatistics statistics,
    RouteStops stops,
    TimeSlot timeSlot
) {

    public SeatForecastInput {
        Objects.requireNonNull(target, "예보에는 어느 차량이 어느 정류장에 도착하는지가 있어야 한다");
        Objects.requireNonNull(trajectory, "예보에는 그 차량의 궤적 재료가 있어야 한다");
        Objects.requireNonNull(statistics, "예보에는 셀 통계가 있어야 한다");
        Objects.requireNonNull(stops, "예보에는 그 판본의 정류장 목록이 있어야 한다");
        Objects.requireNonNull(timeSlot, "예보에는 시간대가 있어야 한다");

        if (statistics.timeSlot() != timeSlot) {
            throw new IllegalArgumentException(
                "설계행렬의 시간대와 셀 통계의 시간대가 다르다: %s, %s".formatted(timeSlot, statistics.timeSlot())
            );
        }

        if (!trajectory.observation().equals(target.observation())) {
            throw new IllegalArgumentException(
                "궤적과 대상이 같은 관측에서 나와야 한다: %s, %s"
                    .formatted(trajectory.observation(), target.observation())
            );
        }
        final long routeVersionId = target.observation().routeVersionId();
        if (statistics.routeVersionId() != routeVersionId) {
            throw new IllegalArgumentException(
                "셀 통계가 다른 노선 판본의 것이다: %d, %d".formatted(routeVersionId, statistics.routeVersionId())
            );
        }
        if (stops.routeVersionId() != routeVersionId) {
            throw new IllegalArgumentException(
                "정류장 목록이 다른 노선 판본의 것이다: %d, %d".formatted(routeVersionId, stops.routeVersionId())
            );
        }
    }

    public ObservedVehicle observation() {
        return target.observation();
    }

    /** 그 차량이 그 노선 판본에서 지금까지 보여 준 가장 많은 잔여석. 비율을 낼 때 나누는 수다. */
    public int maximumSeatsEverObserved() {
        return trajectory.maximumSeatsEverObserved();
    }
}
