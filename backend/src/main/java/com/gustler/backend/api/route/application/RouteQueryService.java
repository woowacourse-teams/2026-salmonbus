package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RouteQueryService {

    private final RouteQueryRepository routeQueryRepository;

    public RouteQueryService(final RouteQueryRepository routeQueryRepository) {
        this.routeQueryRepository = routeQueryRepository;
    }

    public RouteOverview getRouteOverview() {
        final List<Route> routes = routeQueryRepository.findAllCurrentRoutes();
        final RouteStatus status = routeQueryRepository.existsActiveModel()
            ? RouteStatus.FORECAST_READY
            : RouteStatus.PREPARING;

        return new RouteOverview(routes, status);
    }
}
