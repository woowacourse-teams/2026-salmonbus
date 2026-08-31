package com.gustler.backend.processor;

/**
 * 궤적이 끊긴 사유.
 *
 * <p>값이 없는 것과 값이 0 인 것은 다르다. 좌석이 안 변한 것과 좌석 변화를 잴 수 없는 것을
 * 모델이 구분해야 해서, 못 잰 자리는 왜 못 쟀는지를 같이 든다.
 */
public enum TrajectoryGap {

    /** 이력 창에서 이 차를 처음 봤다. 앞에 댈 관측이 없다 */
    NO_EARLIER_OBSERVATION,
    /** 직전 판에 이 차가 없다. 그 판에 차가 없었거나 상류가 이 차를 안 줬다 */
    OBSERVATION_BATCH_MISSING,
    /** 통과 순번이 2 이상 뛰었다. 사이 정류소에서 무슨 일이 있었는지 모른다 */
    STOP_ORDER_JUMPED,
    /** 통과 순번이 되돌아갔다. 차가 새 여정을 시작한 것이다 */
    STOP_ORDER_WENT_BACK,
    /** 여정 키가 서로 다르다. 여정 밖으로 넘어간 좌석 변화는 뜻이 없다 */
    TRIP_KEY_CHANGED,
    /** 좌석을 몰라 변화를 잴 수 없다 */
    SEATS_UNKNOWN,
    /** 그 정류소를 앞서 지난 차가 이력 창에 없다 */
    NO_VEHICLE_AHEAD,
    /** 그 순번에 같은 판으로 들어온 차가 있어 누가 앞인지 가릴 수 없다 */
    ARRIVAL_ORDER_UNKNOWN,
    ;
}
