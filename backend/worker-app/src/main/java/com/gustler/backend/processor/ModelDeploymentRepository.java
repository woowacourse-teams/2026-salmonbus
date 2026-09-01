package com.gustler.backend.processor;

import java.util.Optional;

/**
 * 계수 배포를 읽고 올리는 포트.
 *
 * <p>적재와 승격을 두 걸음으로 나눈다. 먼저 STAGED 로 영수증을 남기고, 계수가 실제로 도는 것을
 * 확인한 다음 ACTIVE 로 올린다. 한 번에 올리면 검사에 걸린 묶음이 잠깐이라도 도는 배포가 된다.
 */
public interface ModelDeploymentRepository {

    /** 도는 배포는 언제나 하나뿐이다. 아직 하나도 없으면 비어 있다. */
    Optional<ActiveModelDeployment> findActive();

    /** 적재 영수증을 STAGED 로 남기고 그 행의 식별자를 낸다. */
    long stage(
        StagedModelDeployment staged
    );

    /**
     * STAGED 행 하나를 ACTIVE 로 올리고 먼저 돌던 것을 RETIRED 로 내린다.
     *
     * <p>올라간 행이 없으면 {@code false} 다. 그 사이 다른 적재가 먼저 올라갔거나 이 행이 이미
     * STAGED 가 아니라는 뜻이라, 덮어쓰지 않고 못 올렸다고 답한다.
     */
    boolean promoteToActive(
        long deploymentId
    );
}
