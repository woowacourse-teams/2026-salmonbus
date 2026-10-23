package com.gustler.backend.forecasting.domain.model;

/**
 * 궤적이 끊긴 사유.
 *
 * <p>값이 없는 것과 값이 0 인 것은 다르다. 모델이 좌석에 변화가 없는 경우와 변화를 측정할 수 없는
 * 경우를 구분하도록 측정할 수 없었던 사유를 함께 보관한다.
 */
public enum TrajectoryGap {
    /** 이력 창에서 처음 관측한 차량으로, 비교할 이전 관측이 없다. */
    NO_EARLIER_OBSERVATION,
    /** 직전 수집 배치에 해당 차량이 없다. 관측 차량이 없었거나 외부 API가 해당 차량을 반환하지 않았다. */
    OBSERVATION_BATCH_MISSING,
    /** 통과 순번이 2 이상 뛰어 중간 정류소의 상황을 알 수 없다. */
    STOP_ORDER_JUMPED,
    /** 통과 순번이 되돌아갔다. 차가 새 여정을 시작한 것이다 */
    STOP_ORDER_WENT_BACK,
    /** 여정 키가 서로 다르다. 다른 여정의 좌석 변화를 비교하는 것은 의미가 없다. */
    TRIP_KEY_CHANGED,
    /** 좌석을 몰라 변화량을 측정할 수 없다. */
    SEATS_UNKNOWN,
    /** 그 정류소를 앞서 지난 차가 이력 창에 없다 */
    NO_VEHICLE_AHEAD,
    /** 같은 수집 배치에서 해당 순번에 진입한 차량이 있어 통과 순서를 구분할 수 없다. */
    ARRIVAL_ORDER_UNKNOWN,
    ;
}
