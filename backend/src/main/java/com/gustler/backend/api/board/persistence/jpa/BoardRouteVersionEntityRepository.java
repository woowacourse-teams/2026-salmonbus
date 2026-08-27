package com.gustler.backend.api.board.persistence.jpa;

import com.gustler.backend.api.route.persistence.jpa.RouteVersionJpaEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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
