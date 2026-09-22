package com.gustler.backend.processor;

/**
 * 잔여석을 모를 때 왜 모르는지.
 *
 * <p>collector 에 같은 뜻의 형이 따로 있다. 형을 공유하지 않고 열의 값으로만 만난다.
 * 원본 사유 둘은 V4의 CHECK를 따른다. QUALITY_WITHHELD는 조회 후 모델 입력에서만 쓰며 원본에 저장하지 않는다.
 */
public enum SeatUnknownReason {

    /** 상류가 모른다고 답했다. 음수로 왔다 */
    REPORTED_UNKNOWN,
    /** 상류가 값을 아예 안 줬다 */
    NOT_REPORTED,
    /** 원본은 보존하고 모델 입력에서만 사용을 보류한다. DB 원본 사유에는 쓰지 않는다. */
    QUALITY_WITHHELD,
    ;
}
