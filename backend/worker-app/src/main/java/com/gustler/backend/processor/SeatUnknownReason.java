package com.gustler.backend.processor;

/**
 * 잔여석을 모를 때 왜 모르는지.
 *
 * <p>collector 에 같은 뜻의 형이 따로 있다. 형을 공유하지 않고 열의 값으로만 만난다.
 * 값 목록은 V4 의 ck_observation_seat_unknown_reason_value 가 둘로 묶어 둔다.
 */
public enum SeatUnknownReason {

    /** 상류가 모른다고 답했다. 음수로 왔다 */
    REPORTED_UNKNOWN,
    /** 상류가 값을 아예 안 줬다 */
    NOT_REPORTED,
    ;
}
