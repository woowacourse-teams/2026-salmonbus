package com.gustler.backend.api.route.infrastructure.jpa;

/**
 * 저장된 모델 배포의 활성 상태를 구분한다.
 * 모델 식별과 배포 전환의 판단에 사용한다.
 */
public enum ModelDeploymentState {
    STAGED,
    ACTIVE,
    RETIRED,
    ;
}
