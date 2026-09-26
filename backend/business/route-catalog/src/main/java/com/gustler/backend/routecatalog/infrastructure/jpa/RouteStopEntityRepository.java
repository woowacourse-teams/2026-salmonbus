package com.gustler.backend.routecatalog.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteStopEntityRepository extends JpaRepository<RouteStopJpaEntity, RouteStopJpaId> {
}
