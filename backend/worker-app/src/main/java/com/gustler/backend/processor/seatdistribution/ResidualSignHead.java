package com.gustler.backend.processor.seatdistribution;

/**
 * 잔차의 방향을 가르는 두 계수 줄.
 *
 * <p>셋 중 둘만 계수로 낸다. 중심과 같을 확률과 중심보다 적을 확률이다. 셋째인 중심보다 많을
 * 확률은 남은 질량이라 계수가 없다.
 *
 * <p><b>선언 순서가 계수 배열의 자리다.</b>
 */
enum ResidualSignHead {

    SAME_SEATS,
    BELOW_ANCHOR,
    ;
}
