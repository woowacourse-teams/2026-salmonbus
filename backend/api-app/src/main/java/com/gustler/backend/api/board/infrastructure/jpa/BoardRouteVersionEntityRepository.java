package com.gustler.backend.api.board.infrastructure.jpa;

import com.gustler.backend.api.route.infrastructure.jpa.RouteVersionJpaEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 탑승판이 사용할 현재 노선 판본을 읽는 JPA 경계다.
 */
public interface BoardRouteVersionEntityRepository
    extends Repository<RouteVersionJpaEntity, Long> {
    @Query("""
        SELECT version
        FROM RouteVersionJpaEntity version
        JOIN FETCH version.route
        WHERE version.route.sourceRouteId = :sourceRouteId
          AND version.validTo IS NULL
        """)
    Optional<RouteVersionJpaEntity> findCurrent(
        @Param("sourceRouteId") String sourceRouteId
    );
}
