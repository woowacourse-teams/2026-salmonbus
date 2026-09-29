package com.gustler.backend.api.route.infrastructure.jpa;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface ModelDeploymentEntityRepository
    extends Repository<ModelDeploymentJpaEntity, Long> {

    boolean existsByState(ModelDeploymentState state);

    Optional<ModelDeploymentJpaEntity> findByState(ModelDeploymentState state);
}
