package com.gustler.backend.api.route.infrastructure.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelDeploymentEntityRepository
    extends JpaRepository<ModelDeploymentJpaEntity, Long> {

    boolean existsByState(ModelDeploymentState state);

    Optional<ModelDeploymentJpaEntity> findByState(ModelDeploymentState state);
}
