package com.gustler.backend.api.route.infrastructure.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface RouteEntityRepository extends Repository<RouteJpaEntity, Long> {

    @Query("""
        SELECT version.route
        FROM RouteVersionJpaEntity version
        WHERE version.validTo IS NULL
        ORDER BY version.route.id
        """)
    List<RouteJpaEntity> findAllCurrentRoutes();

    @Query("""
        SELECT version.route.sourceRouteId
        FROM RouteVersionJpaEntity version
        WHERE version.validTo IS NULL
          AND EXISTS (
              SELECT publication.id
              FROM RouteForecastPublicationJpaEntity publication
              WHERE publication.routeVersionId = version.id
          )
        """)
    List<String> findForecastPublishedRouteIds();
}
