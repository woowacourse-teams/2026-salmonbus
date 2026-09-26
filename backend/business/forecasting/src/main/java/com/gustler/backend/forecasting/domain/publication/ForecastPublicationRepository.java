package com.gustler.backend.forecasting.domain.publication;

import java.util.Optional;

public interface ForecastPublicationRepository {

    Optional<PublishedForecast> findBySourceBatchId(long sourceBatchId);

    /** 발행, 예측과 최초 평가 상태를 같은 트랜잭션에 저장한다. 기존 발행을 덮어쓰지 않는다. */
    PublishedForecast save(ForecastPublication publication);
}
