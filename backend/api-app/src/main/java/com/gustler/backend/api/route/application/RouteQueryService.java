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

/**
 * 현재 노선 목록과 모델·예보 준비 상태를 조합해 공개 노선 목록을 만든다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
@RequiredArgsConstructor
public class RouteQueryService {
    private final RouteQueryRepository routeQueryRepository;

    /**
     * 현재 카탈로그와 모델 지원·발행 정보를 읽어 목록을 조립한다. 미발행 노선에 새 예측을 생성하지 않는다.
     */
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
