package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.domain.CurrentRoute;
import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteServiceStatus;
import com.gustler.backend.api.route.domain.RouteStatus;
import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperations;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 현재 노선 목록과 모델·예보 준비 상태를 조합해 공개 노선 목록을 만든다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
@RequiredArgsConstructor
public class RouteQueryService {
    private final RouteQueryRepository routeQueryRepository;

    private Clock clock;
    private RouteOperations operations;

    @Autowired
    public void setClock(Clock value) {
        clock = value;
    }

    @Autowired(required = false)
    public void setOperations(RouteOperations value) {
        operations = value;
    }

    /**
     * 현재 카탈로그와 모델 지원·발행 정보를 읽어 목록을 조립한다. 미발행 노선에 새 예측을 생성하지 않는다.
     */
    public RouteOverview getRouteOverview() {
        List<Route> routes = routeQueryRepository.findAllCurrentRoutes();
        Map<String, RouteOperation> states = operationStates();
        if (operations != null) {
            routes = routes.stream()
                .filter(route -> states.containsKey(route.id()) && states.get(route.id()).everPublished())
                .toList();
        }
        final boolean activeModelExists = routeQueryRepository.existsActiveModel();
        Set<String> forecastPublishedRouteIds = routeQueryRepository.findForecastPublishedRouteIds();

        return new RouteOverview(routes.stream()
            .map(route -> currentRoute(route, states, activeModelExists, forecastPublishedRouteIds))
            .toList());
    }

    private CurrentRoute currentRoute(
        Route route,
        Map<String, RouteOperation> states,
        final boolean activeModelExists,
        Set<String> publishedRouteIds
    ) {
        RouteOperation operation = states.get(route.id());
        final boolean published = publishedRouteIds.contains(route.id()) && forecastAllowed(operation);
        return new CurrentRoute(route, RouteStatus.from(activeModelExists, published), routeNotice(operation));
    }

    private boolean forecastAllowed(RouteOperation operation) {
        return operations == null || operation.serviceEnabled();
    }

    private Map<String, RouteOperation> operationStates() {
        if (operations == null) {
            return Map.of();
        }
        return operations.list().stream()
            .collect(Collectors.toMap(RouteOperation::routeId, Function.identity()));
    }

    private RouteServiceStatus routeNotice(RouteOperation operation) {
        if (operations == null) {
            return null;
        }
        return notice(operation);
    }

    private RouteServiceStatus notice(RouteOperation row) {
        Instant now = clock.instant();
        return new RouteServiceStatus(
            row.routeId(),
            true,
            row.state(),
            row.serviceEnabled(),
            row.collectionEnabled(),
            row.reason(),
            row.message(),
            row.sourceUrl(),
            now,
            now.plusSeconds(30));
    }
}
