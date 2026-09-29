package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.domain.CurrentRoute;
import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RouteQueryService {

    private final RouteQueryRepository routeQueryRepository;

    public RouteOverview getRouteOverview() {
        List<Route> routes = routeQueryRepository.findAllCurrentRoutes();
        final boolean activeModelExists = routeQueryRepository.existsActiveModel();
        Set<String> forecastPublishedRouteIds = routeQueryRepository.findForecastPublishedRouteIds();

        return new RouteOverview(routes.stream()
            .map(route -> new CurrentRoute(
                route,
                RouteStatus.from(activeModelExists, forecastPublishedRouteIds.contains(route.id()))
            ))
            .toList());
    }
}
