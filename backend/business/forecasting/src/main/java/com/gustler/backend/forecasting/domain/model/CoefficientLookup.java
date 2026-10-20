package com.gustler.backend.forecasting.domain.model;

/**
 * 노선과 예보 거리로 계수 묶음을 선택한다.
 *
 * <p>예측기는 파일에서 읽은 계수와 테스트에서 만든 계수를 같은 방식으로 조회하며,
 * 계수의 출처를 알 필요가 없다.
 */
public interface CoefficientLookup {
    /** 목록에 없는 노선이나 범위 밖 거리에는 예외를 던진다. 누락된 계수를 임의로 만들지 않는다. */
    HorizonCoefficients at(
        String modelRoute,
        int stopsAhead
    );
}
