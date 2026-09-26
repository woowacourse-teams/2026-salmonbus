package com.gustler.backend.api.vehicle.infrastructure.jpa;

import com.gustler.backend.api.route.infrastructure.jpa.RouteVersionJpaEntity;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface VehicleRouteVersionEntityRepository
    extends Repository<RouteVersionJpaEntity, Long> {

    Optional<RouteVersionJpaEntity> findByRoute_SourceRouteIdAndValidToIsNull(
        String sourceRouteId
    );
}
