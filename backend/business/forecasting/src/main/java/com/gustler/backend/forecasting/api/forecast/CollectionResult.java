package com.gustler.backend.forecasting.api.forecast;

/**
 * 예보가 처리한 관측 배치의 수집 결과다. 수집에 실패한 배치는 예보가 처리하지 않으므로 값이 없다.
 */
public enum CollectionResult {
    /** 응답에 운행 중인 버스가 있었다. */
    SUCCESS,
    /** 응답은 정상이지만 운행 중인 버스가 없었다. */
    EMPTY
}
