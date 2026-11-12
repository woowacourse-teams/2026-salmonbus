package com.gustler.backend.api.board.infrastructure.jpa;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 노선 판본의 방문 순서를 유지해 탑승판 정류장을 읽는 JPA 경계다.
 */
public interface BoardRouteStopEntityRepository
    extends Repository<RouteStopJpaEntity, RouteStopJpaId> {
    @Query("""
        SELECT stop
        FROM BoardRouteStopJpaEntity stop
        WHERE stop.id.routeVersionId = :routeVersionId
        ORDER BY stop.id.stopOrder
        """)
    List<RouteStopJpaEntity> findAllByRouteVersionId(
        @Param("routeVersionId") long routeVersionId
    );

    /**
     * 여러 노선 판본의 정류장을 판본, 방문 순번 순서로 읽는다.
     */
    @Query("""
        SELECT stop
        FROM BoardRouteStopJpaEntity stop
        WHERE stop.id.routeVersionId IN :routeVersionIds
        ORDER BY stop.id.routeVersionId, stop.id.stopOrder
        """)
    List<RouteStopJpaEntity> findAllByRouteVersionIdIn(
        @Param("routeVersionIds") Collection<Long> routeVersionIds
    );
}
