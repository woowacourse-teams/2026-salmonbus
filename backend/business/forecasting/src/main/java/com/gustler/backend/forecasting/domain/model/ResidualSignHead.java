package com.gustler.backend.forecasting.domain.model;

/**
 * 잔차의 방향을 구분하는 두 계수 배열.
 *
 * <p>세 방향 중 중심과 같을 확률과 중심보다 적을 확률만 계수로 계산한다. 세 번째인 중심보다 많을
 * 확률은 남은 질량으로 계산하므로 계수가 없다.
 *
 * <p>선언 순서가 계수 배열의 인덱스를 결정한다.
 */
public enum ResidualSignHead {
    SAME_SEATS,
    BELOW_ANCHOR,
    ;
}
