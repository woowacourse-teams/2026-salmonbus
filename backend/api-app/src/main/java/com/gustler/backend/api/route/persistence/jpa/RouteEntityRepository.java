package com.gustler.backend.api.route.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RouteEntityRepository extends JpaRepository<RouteJpaEntity, Long> {

    @Query("""
        SELECT version.route
        FROM RouteVersionJpaEntity version
        WHERE version.validTo IS NULL
        ORDER BY version.route.id
        """)
    List<RouteJpaEntity> findAllCurrentRoutes();
}
