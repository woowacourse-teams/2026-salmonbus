package com.gustler.backend.api.board.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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
}
