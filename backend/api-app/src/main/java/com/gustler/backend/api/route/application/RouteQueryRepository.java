package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.domain.Route;
import java.util.List;
import java.util.Set;

public interface RouteQueryRepository {

    List<Route> findAllCurrentRoutes();

    boolean existsActiveModel();

    Set<String> findForecastPublishedRouteIds();
}
