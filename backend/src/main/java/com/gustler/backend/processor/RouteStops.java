package com.gustler.backend.processor;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 노선 판본이 지나는 정류장 전부. 순번 오름차순이다.
 *
 * <p>차량 하나를 주면 그 앞의 예보 대상을 골라 준다. 대상에서 빠지는 자리가 셋이고
 * 셋 다 여기서 걸린다.
 *
 * <ul>
 *   <li>승차할 수 없는 경유 지점
 *   <li>12정류장보다 먼 자리
 *   <li>종점을 넘어 순번 1로 돌아가는 자리. 그 차량이 그 여정을 또 돌지 우리는 모른다.
 *       순번 차이가 음수가 되어 저절로 빠진다
 * </ul>
 */
public record RouteStops(
    long routeVersionId,
    List<RouteStop> stops
) {

    public RouteStops {
        stops = List.copyOf(stops);
        for (RouteStop stop : stops) {
            if (stop.routeVersionId() != routeVersionId) {
                throw new IllegalArgumentException(
                    "다른 노선 판본의 정류장가 섞였다: %d, %d".formatted(routeVersionId, stop.routeVersionId())
                );
            }
        }
    }

    /**
     * 그 차량이 앞으로 지날 정류장 중 예보를 낼 곳.
     *
     * <p>잔여석을 모르는 차량은 설계행렬을 채울 수 없어 예보를 내지 않는다. 빈 목록으로 답한다.
     */
    public List<VehicleStopTarget> targetsAheadOf(
        ObservedVehicle observation
    ) {
        if (!observation.hasKnownSeats()) {
            return List.of();
        }
        List<VehicleStopTarget> targets = new ArrayList<>();
        for (RouteStop stop : stops) {
            if (isTargetAheadOf(observation, stop)) {
                targets.add(new VehicleStopTarget(observation, stop));
            }
        }
        return List.copyOf(targets);
    }

    private static boolean isTargetAheadOf(
        ObservedVehicle observation,
        RouteStop stop
    ) {
        if (!stop.boardingAllowed()) {
            return false;
        }
        return ForecastDistance.covers(stop.stopOrder() - observation.passedStopOrder());
    }
}
