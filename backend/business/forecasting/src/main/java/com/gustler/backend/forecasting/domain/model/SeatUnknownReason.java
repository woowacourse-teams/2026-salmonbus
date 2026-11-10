package com.gustler.backend.forecasting.domain.model;

/**
 * 잔여석을 알 수 없는 사유.
 *
 * <p>observations 에 같은 의미의 타입이 따로 있다. 타입을 공유하지 않고 열의 값으로 변환한다.
 * 원본 사유 둘은 V4의 CHECK를 따른다.
 */
public enum SeatUnknownReason {
    /** 상류가 미확인을 보고했거나 수집 단계에서 잔여석 사용을 보류했다. */
    REPORTED_UNKNOWN,
    /** 상류가 잔여석 값을 제공하지 않았다. */
    NOT_REPORTED,
    ;
}
