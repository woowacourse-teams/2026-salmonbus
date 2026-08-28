package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RouteQueryService {

    private final RouteQueryRepository routeQueryRepository;

    public RouteOverview getRouteOverview() {
        List<Route> routes = routeQueryRepository.findAllCurrentRoutes();
        RouteStatus status = RouteStatus.from(routeQueryRepository.existsActiveModel());

        return new RouteOverview(routes, status);
    }
}
