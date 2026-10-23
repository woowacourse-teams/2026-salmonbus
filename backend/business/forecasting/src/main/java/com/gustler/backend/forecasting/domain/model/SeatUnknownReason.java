package com.gustler.backend.forecasting.domain.model;

/**
 * 잔여석을 알 수 없는 사유.
 *
 * <p>observations 에 같은 의미의 타입이 따로 있다. 타입을 공유하지 않고 열의 값으로 변환한다.
 * 원본 사유 둘은 V4의 CHECK를 따른다. QUALITY_WITHHELD는 조회 후 모델 입력에서만 쓰며 원본에 저장하지 않는다.
 */
public enum SeatUnknownReason {
    /** 상류가 잔여석을 모른다는 뜻으로 음수를 반환했다. */
    REPORTED_UNKNOWN,
    /** 상류가 잔여석 값을 제공하지 않았다. */
    NOT_REPORTED,
    /** 원본은 보존하고 모델 입력에서만 사용을 보류한다. DB 원본 사유에는 쓰지 않는다. */
    QUALITY_WITHHELD,
    ;
}
