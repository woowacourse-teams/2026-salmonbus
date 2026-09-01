package com.gustler.backend.processor.seatdistribution;

import java.util.Map;

/**
 * 우리 노선 판본이 가리키는 GBIS 노선을 계수 묶음이 쓰는 노선 이름으로 옮긴다.
 *
 * <p>우리 DB 는 노선 판본 id 로 돌고 계수 묶음은 <b>1650</b> · <b>3330</b> 두 이름으로 돈다.
 * 그 사이를 잇는 것이 GBIS 노선 id 다. 노선 개편으로 판본이 바뀌어도 GBIS 노선 id 는 그대로라
 * 계수를 계속 찾는다.
 *
 * <p>목록에 없는 노선은 옮기지 않는다. 지어내면 다른 노선 계수로 예보하게 된다.
 */
public final class ModelRoute {

    private static final Map<String, String> BY_UPSTREAM_ROUTE_ID = Map.of(
        "234000050", "1650",
        "204000057", "3330");

    private ModelRoute() {
    }

    public static String of(
        String upstreamRouteId
    ) {
        String modelRoute = BY_UPSTREAM_ROUTE_ID.get(upstreamRouteId);
        if (modelRoute == null) {
            throw new IllegalArgumentException(
                "계수 묶음이 안 담는 GBIS 노선이다: " + upstreamRouteId);
        }
        return modelRoute;
    }

    public static boolean covers(
        String upstreamRouteId
    ) {
        return BY_UPSTREAM_ROUTE_ID.containsKey(upstreamRouteId);
    }
}
