package com.gustler.backend.api.route.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelDeploymentEntityRepository
    extends JpaRepository<ModelDeploymentJpaEntity, Long> {

    boolean existsByState(ModelDeploymentState state);
}
