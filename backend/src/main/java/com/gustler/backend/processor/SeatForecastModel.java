package com.gustler.backend.processor;

/**
 * 재료를 받아 좌석 분포를 내는 모델.
 *
 * <p><b>DB 를 읽지 않는다.</b> 필요한 것을 전부 {@link SeatForecastInput} 으로 받아 계산만 한다.
 * 그래야 과거 시점 재료를 손으로 만들어 넣는 백테스트가 된다.
 *
 * <p>구현체가 아직 없다. 좌석 분포를 만드는 네 단계(기준점 · 부호 · 크기 구간 · 합치기)가
 * 전부 밖에서 배운 계수를 쓰는데 그 계수가 아직 없다. 구현체가 없는 동안 예보 배치는
 * batch 를 하나도 안 연다.
 */
public interface SeatForecastModel {

    SeatForecastResult predict(
        SeatForecastInput input
    );
}
