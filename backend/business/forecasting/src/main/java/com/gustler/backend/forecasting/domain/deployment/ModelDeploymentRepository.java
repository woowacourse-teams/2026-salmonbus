package com.gustler.backend.forecasting.domain.deployment;

import java.util.Optional;

/**
 * 계수 배포의 조회와 승격을 담당하는 포트다.
 *
 * <p>적재와 승격을 두 단계로 나눈다. 먼저 STAGED로 적재 기록을 남기고 계수가 실제로 동작하는지
 * 확인한 다음 ACTIVE로 승격한다. 한 번에 승격하면 검사에서 탈락할 묶음도 잠깐 활성 배포가 된다.
 */
public interface ModelDeploymentRepository {
    /** 활성 배포는 언제나 하나뿐이다. 아직 없으면 빈 결과를 반환한다. */
    Optional<ActiveModelDeployment> findActive();

    /** 적재 기록을 STAGED 로 남기고 해당 행의 식별자를 반환한다. */
    long stage(
        StagedModelDeployment staged
    );

    /**
     * STAGED 행 하나를 ACTIVE로 승격하고 기존 활성 배포를 RETIRED로 전환한다.
     *
     * <p>승격한 행이 없으면 {@code false}를 반환한다. 그 사이 다른 적재가 먼저 승격했거나 이 행이 이미
     * STAGED가 아니라는 뜻이므로 덮어쓰지 않고 승격 실패를 반환한다.
     */
    boolean promoteToActive(
        long deploymentId
    );
}
