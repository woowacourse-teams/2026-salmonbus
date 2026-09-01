package com.gustler.backend.collector.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteStopEntityRepository extends JpaRepository<RouteStopJpaEntity, RouteStopJpaId> {
}
