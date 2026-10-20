package com.gustler.backend.api.route.infrastructure.jpa;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * API 조회가 활성 모델 배포의 존재와 메타데이터를 읽는 JPA 경계다.
 */
public interface ModelDeploymentEntityRepository
    extends Repository<ModelDeploymentJpaEntity, Long> {
    boolean existsByState(ModelDeploymentState state);

    Optional<ModelDeploymentJpaEntity> findByState(ModelDeploymentState state);
}
