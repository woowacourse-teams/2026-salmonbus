package com.gustler.backend.processor.a18;

/**
 * 잔차가 어느 쪽으로 어긋났는가.
 *
 * <p>잔차는 중심 좌석에서 도착 좌석을 뺀 값이다. 잔차가 양수면 도착했을 때 좌석이 중심보다
 * 적었다는 뜻이다.
 *
 * <p><b>선언 순서가 계수 배열의 자리다.</b> 좌석이 중심보다 적은 쪽이 0번, 많은 쪽이 1번이다.
 * 뒤집으면 분포가 좌우로 뒤집힌 채로 합이 1 이라 어떤 검증에도 안 걸린다.
 */
enum ResidualDirection {

    SEATS_BELOW_ANCHOR,
    SEATS_ABOVE_ANCHOR,
    ;
}
