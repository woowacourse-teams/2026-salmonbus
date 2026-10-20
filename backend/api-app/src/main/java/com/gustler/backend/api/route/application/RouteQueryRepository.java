package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.domain.Route;
import java.util.List;
import java.util.Set;

/**
 * 공개 노선 목록에 필요한 현재 판본과 모델·발행 준비 상태를 읽는 경계다.
 */
public interface RouteQueryRepository {
    /**
     * 현재 유효한 노선 목록을 읽는다. 과거 판본을 노선 목록에 섞지 않는다.
     */
    List<Route> findAllCurrentRoutes();

    boolean existsActiveModel();

    /**
     * 발행된 예측이 있는 노선 식별자를 읽는다. 조회 중 새 예측을 계산하지 않는다.
     */
    Set<String> findForecastPublishedRouteIds();
}
