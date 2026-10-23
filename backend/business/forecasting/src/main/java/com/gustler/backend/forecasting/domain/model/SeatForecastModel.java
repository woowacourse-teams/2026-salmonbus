package com.gustler.backend.forecasting.domain.model;

/**
 * 입력을 받아 좌석 분포를 계산하는 모델.
 *
 * <p>DB 를 읽지 않고 필요한 값을 전부 {@link SeatForecastInput} 으로 받아 계산한다.
 * 과거 시점의 입력을 구성해 전달하는 백테스트도 같은 경로로 수행한다.
 *
 * <p>좌석 입력이 모델의 지원 범위를 벗어나면 {@link SeatRangeException} 으로 구별한다.
 * 지원 범위는 각 모델이 결정하며 호출자가 구체 모델의 상한을 복제하지 않는다.
 * 그 밖의 계산·설정 오류는 좌석 범위 오류로 바꾸지 않는다.
 */
public interface SeatForecastModel {
    default boolean supportsRoute(RouteStops stops) {
        return true;
    }

    /**
     * 주어진 과거 시점 입력만으로 예보를 계산한다. DB나 원천 API 조회 없이 모델 입력의 노선·예측 거리를 적용한다.
     */
    SeatForecastResult predict(
        SeatForecastInput input
    );
}
