package com.gustler.backend.processor.seatdistribution;

/**
 * 노선과 예보 거리로 계수 묶음을 고르는 자리.
 *
 * <p>계수를 어디서 읽어 왔는지는 예측기가 몰라도 된다. 파일에서 읽은 것이든 테스트가 만든
 * 것이든 고르는 방법만 같으면 된다.
 */
public interface CoefficientLookup {

    /** 목록에 없는 노선이나 범위 밖 거리면 예외를 던진다. 없는 계수를 지어내지 않는다. */
    HorizonCoefficients at(
        String modelRoute,
        int stopsAhead
    );
}
