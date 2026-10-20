package com.gustler.backend.forecasting.api.quality;

/**
 * 명시된 관측 범위의 조사 근거 미리보기다.
 * 전체 데이터의 조사 완료나 실제 수정 결과를 뜻하지 않는다.
 */
public record TripQualityPreview(String scope, long sampledBatches, long sampledObservations, long sampledAboveRange) {
}
