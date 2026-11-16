package com.gustler.backend.api.route.infrastructure.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * 현재 공개 노선과 예보 실행 줄이 있는 노선의 식별을 읽는 JPA 경계다.
 */
public interface RouteEntityRepository extends Repository<RouteJpaEntity, Long> {
    @Query("""
        SELECT version.route
        FROM RouteVersionJpaEntity version
        WHERE version.validTo IS NULL
        ORDER BY version.route.id
        """)
    List<RouteJpaEntity> findAllCurrentRoutes();

    /**
     * 현재 판본에 예보 실행 줄({@code forecast_run})이 하나라도 있는 노선을 읽는다. 예측이 0건인 실행 줄도 센다.
     */
    @Query(nativeQuery = true, value = """
        SELECT route.source_route_id
        FROM route_version version
        JOIN route ON route.id = version.route_id
        WHERE version.valid_to IS NULL
          AND EXISTS (SELECT 1 FROM forecast_run run WHERE run.route_version_id = version.id)
        """)
    List<String> findForecastPublishedRouteIds();
}
