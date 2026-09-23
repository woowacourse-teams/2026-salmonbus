package com.gustler.backend.forecasting.domain.publication;

/** 이미 저장한 발행의 식별 정보. 재요청은 같은 결과를 반환한다. */
public record PublishedForecast(long publicationId, int predictionCount) {
}
