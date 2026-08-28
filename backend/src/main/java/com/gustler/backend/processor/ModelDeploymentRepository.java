package com.gustler.backend.processor;

import java.util.Optional;

/**
 * 지금 도는 계수 배포를 읽는 포트.
 *
 * <p>읽기만 둔다. 적재와 승격은 SAL-93 몫이고 그때 이 포트가 자란다.
 */
public interface ModelDeploymentRepository {

    /** 도는 배포는 언제나 하나뿐이다. 아직 하나도 없으면 비어 있다. */
    Optional<ActiveModelDeployment> findActive();
}
