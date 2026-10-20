package com.gustler.backend.api.vehicle.infrastructure.jpa;

import com.gustler.backend.api.route.infrastructure.jpa.RouteVersionJpaEntity;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * 차량 조회에 사용할 공개 노선의 현재 판본을 읽는 JPA 경계다.
 */
public interface VehicleRouteVersionEntityRepository
    extends Repository<RouteVersionJpaEntity, Long> {
    Optional<RouteVersionJpaEntity> findByRoute_SourceRouteIdAndValidToIsNull(
        String sourceRouteId
    );
}
