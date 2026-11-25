package com.gustler.backend.forecasting.domain.deployment;

import java.util.Optional;

/**
 * 활성 배포 정보와 메모리에 적재된 모델을 같은 기준으로 조회한다.
 *
 * <p>따로 조회하면 모델 전환 중 A로 계산한 예보를 B의 결과로 기록할 수 있다.
 * 배치마다 한 번 조회하고 끝까지 사용해 모든 예보에 같은 모델을 적용한다.
 */
public interface ForecastRuntime {

    /** 계수가 없거나 배포와 계수의 식별 정보가 일치하지 않으면 빈 결과를 반환한다. */
    Optional<RuntimeSnapshot> resolveActive();
}
