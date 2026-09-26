package com.gustler.backend.routecatalog.infrastructure.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteVersionEntityRepository extends JpaRepository<RouteVersionJpaEntity, Long> {

    Optional<RouteVersionJpaEntity> findFirstByRouteIdOrderByValidFromDescIdDesc(
        long routeId
    );
}
