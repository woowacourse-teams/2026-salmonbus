package com.gustler.backend.forecasting.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 한 노선 버전의 전체 정류장. 순번 오름차순으로 정렬한다.
 *
 * <p>Open API 노선 ID를 함께 보관한다. DB의 노선 버전 ID와 모델 계수가 사용하는 노선 이름을
 * 연결하므로 모델은 DB에 접근하지 않고도 계수를 선택할 수 있다.
 *
 * <p>차량이 앞으로 지날 정류장 중 예보 대상을 선택하며 다음 세 경우를 제외한다.
 *
 * <ul>
 *   <li>승차할 수 없는 경유 지점
 *   <li>12정류장보다 먼 정류장
 *   <li>종점을 넘어 순번 1로 돌아가는 정류장. 해당 차량이 새 여정을 운행할지는 알 수 없으며,
 *       순번 차이가 음수이므로 자동으로 제외된다
 * </ul>
 */
public record RouteStops(
    long routeVersionId,
    String sourceRouteId,
    List<RouteStop> stops,
    String routeName
) {
    public RouteStops(final long routeVersionId, String sourceRouteId, List<RouteStop> stops) {
        this(routeVersionId, sourceRouteId, stops, "");
    }

    public RouteStops {
        routeName = Objects.toString(routeName, "");
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
     * <p>정류장 위치를 0에서 1 사이로 정규화할 때의 분모로, 목록 크기가 아니라 최대 순번이다.
     * 목록에는 승차할 수 없는 경유 지점도 포함되어 두 값이 같아 보이지만,
     * 순번이 건너뛰는 노선 버전에서는 다르다. 순번을 정규화하므로 최대 순번으로 나눈다.
     */
    public int largestStopOrder() {
        return stops.stream()
            .mapToInt(RouteStop::stopOrder)
            .max()
            .orElseThrow(() -> new IllegalStateException(
                "정류장이 하나도 없는 노선 판본에는 마지막 순번이 없다: " + routeVersionId));
    }

    /**
     * 차량이 앞으로 지날 정류장 중 예보를 생성할 대상.
     *
     * <p>잔여석을 모르는 차량은 설계행렬을 채울 수 없어 예보를 생성하지 않고 빈 목록을 반환한다.
     */
    public List<VehicleStopTarget> targetsAheadOf(
        ObservedVehicle observation
    ) {
        if (!observation.hasKnownSeats()) {
            return List.of();
        }
        List<VehicleStopTarget> targets = stops.stream()
            .filter(stop -> isTargetAheadOf(observation, stop))
            .map(stop -> new VehicleStopTarget(observation, stop))
            .collect(Collectors.toCollection(ArrayList::new));
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
