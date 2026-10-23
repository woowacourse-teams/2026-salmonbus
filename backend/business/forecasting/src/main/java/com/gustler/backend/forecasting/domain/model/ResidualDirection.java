package com.gustler.backend.forecasting.domain.model;

/**
 * 잔차가 어긋난 방향.
 *
 * <p>잔차는 중심 좌석에서 도착 좌석을 뺀 값이다. 잔차가 양수면 도착했을 때 좌석이 중심보다
 * 적었다는 뜻이다.
 *
 * <p>선언 순서가 계수 배열의 인덱스를 결정한다. 좌석이 중심보다 적은 쪽이 0번, 많은 쪽이 1번이다.
 * 순서를 뒤집으면 분포의 좌우가 바뀌어도 합은 1 이므로 검증으로 구분할 수 없다.
 */
public enum ResidualDirection {
    SEATS_BELOW_ANCHOR,
    SEATS_ABOVE_ANCHOR,
    ;
}
