package com.gustler.backend.forecasting.api.pipeline;

/**
 * 관측 배치 하나의 수집 결과다. 채점 단계는 이 값으로 수집 구멍을 직접 안다.
 */
public enum CollectionResult {
    /** 응답에 운행 중인 버스가 있었다. */
    SUCCESS,
    /** 응답은 정상이지만 운행 중인 버스가 없었다. */
    EMPTY,
    /** 응답을 받지 못했거나 정상 응답이 아니었다. */
    FAILED
}
