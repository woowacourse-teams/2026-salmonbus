package com.gustler.backend.routecatalog.infrastructure.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 한 노선의 가장 최근 판본을 유효 시각과 식별 순서로 읽는 JPA 경계다.
 */
public interface RouteVersionEntityRepository extends JpaRepository<RouteVersionJpaEntity, Long> {
    Optional<RouteVersionJpaEntity> findFirstByRouteIdOrderByValidFromDescIdDesc(
        long routeId
    );
}
