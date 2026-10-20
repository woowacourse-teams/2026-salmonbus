package com.gustler.backend.routecatalog.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 노선 판본 안의 정류장 방문 행을 저장하는 JPA 경계다.
 */
public interface RouteStopEntityRepository extends JpaRepository<RouteStopJpaEntity, RouteStopJpaId> {
}
