package com.gustler.backend.forecasting.domain.model;

import java.util.Map;

/**
 * 노선 버전에 연결된 GBIS 노선 ID를 모델 계수에서 사용하는 노선 이름으로 변환한다.
 *
 * <p>DB는 노선 버전 ID를 사용하고 모델 계수는 <b>1650</b>·<b>3330</b> 두 이름을 사용한다.
 * GBIS 노선 ID로 둘을 연결한다. 노선 개편으로 버전이 바뀌어도 GBIS 노선 ID가 같으면
 * 같은 노선의 계수를 찾을 수 있다.
 *
 * <p>목록에 없는 노선을 임의로 변환하면 다른 노선의 계수를 사용할 수 있으므로 거절한다.
 */
public final class ModelRoute {

    private static final Map<String, String> BY_SOURCE_ROUTE_ID = Map.of(
        "234000050", "1650",
        "204000057", "3330");

    private ModelRoute() {
    }

    public static String of(
        String sourceRouteId
    ) {
        String modelRoute = BY_SOURCE_ROUTE_ID.get(sourceRouteId);
        if (modelRoute == null) {
            throw new IllegalArgumentException(
                "계수 묶음이 안 담는 GBIS 노선이다: " + sourceRouteId);
        }
        return modelRoute;
    }

    public static boolean covers(
        String sourceRouteId
    ) {
        return BY_SOURCE_ROUTE_ID.containsKey(sourceRouteId);
    }
}
