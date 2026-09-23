package com.gustler.backend.forecasting.domain.model;


import java.util.ArrayList;
import java.util.List;

/**
 * 한 노선 버전의 전체 정류장. 순번 오름차순으로 정렬한다.
 *
 * <p>Open API 노선 ID를 함께 보관한다. DB의 노선 버전 ID와 모델 계수가 사용하는 노선 이름을
 * 연결하므로 모델은 DB에 접근하지 않고도 계수를 선택할 수 있다.
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
    String sourceRouteId,
    List<RouteStop> stops
) {

    public RouteStops {
        if (sourceRouteId == null || sourceRouteId.isBlank()) {
            throw new IllegalArgumentException("정류장 목록에는 어느 Open API 노선인지가 있어야 한다");
        }
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
     * 이 노선 버전의 마지막 정류장 순번.
     *
     * <p>정류장 위치를 0에서 1 사이로 정규화할 때 나누는 수다. 목록의 크기가 아니라 가장 큰 순번이다.
     * 승차할 수 없는 경유 지점도 순번을 하나 차지하는데 목록에는 그것까지 들어 있어서 둘이 같아 보이지만,
     * 순번이 건너뛰는 노선 버전에서는 둘이 다르다. 순번을 정규화하므로 순번의 최대값으로 나눈다.
     */
    public int largestStopOrder() {
        return stops.stream()
            .mapToInt(RouteStop::stopOrder)
            .max()
            .orElseThrow(() -> new IllegalStateException(
                "정류장이 하나도 없는 노선 판본에는 마지막 순번이 없다: " + routeVersionId));
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
