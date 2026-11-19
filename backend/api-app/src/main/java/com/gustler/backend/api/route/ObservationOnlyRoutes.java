package com.gustler.backend.api.route;

import java.util.Set;

public final class ObservationOnlyRoutes {
    private static final Set<String> ROUTE_IDS = Set.of(
        "234001476", "222000209", "227000016", "234000079", "234000015"
    );

    private ObservationOnlyRoutes() {
    }

    public static boolean includes(String routeId) {
        return ROUTE_IDS.contains(routeId);
    }

    public static void requirePublic(RouteId routeId) {
        if (includes(routeId.value())) {
            throw new RouteNotFoundException();
        }
    }
}
