package com.gustler.backend.forecasting.domain.model;

import com.gustler.backend.forecasting.domain.model.RuntimeSnapshot;
import java.util.Optional;

/**
 * 현재 예보에 사용할 배포 정보와 모델을 함께 반환하는 포트.
 *
 * <p>배포 정보와 모델을 따로 읽으면 모델 활성화 시점에 서로 다른 버전이 선택될 수 있다.
 * 예를 들어 A의 계수로 계산한 결과에 B의 배포 정보를 기록할 수 있으므로 함께 읽는다.
 *
 * <p>배치 하나는 이것을 한 번 받아 끝까지 쓴다. 그래서 그 배치의 모든 예보 행이 같은 계수에서 나온다.
 */
public interface ForecastRuntime {

    /** 계수가 없거나 배포와 계수의 식별 정보가 일치하지 않으면 빈 결과를 반환한다. */
    Optional<RuntimeSnapshot> resolveActive();
}
