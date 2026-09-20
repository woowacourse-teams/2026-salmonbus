package com.gustler.backend.forecast.model;

import com.gustler.backend.processor.SeatForecastInput;
import com.gustler.backend.processor.SeatForecastResult;

/**
 * 재료를 받아 좌석 분포를 내는 모델.
 *
 * <p><b>DB 를 읽지 않는다.</b> 필요한 것을 전부 {@link SeatForecastInput} 으로 받아 계산만 한다.
 * 그래야 과거 시점 재료를 손으로 만들어 넣는 백테스트가 된다.
 *
 * <p>좌석 입력이 모델의 지원 범위를 벗어나면 {@link SeatRangeException} 으로 구별한다.
 * 지원 범위는 각 모델이 결정하며 호출자가 구체 모델의 상한을 복제하지 않는다.
 * 그 밖의 계산·설정 오류는 좌석 범위 오류로 바꾸지 않는다.
 */
public interface SeatForecastModel {

    /** @throws SeatRangeException 좌석 입력이 이 모델의 지원 범위를 벗어난 경우 */
    SeatForecastResult predict(
        SeatForecastInput input
    );
}
