package com.gustler.backend.forecasting.domain.publication;

public interface ForecastPublicationRepository {
    /** 발행과 예측을 같은 트랜잭션에 저장한다. 같은 배치를 다시 발행하면 예측 값을 덮어쓴다. 최초 발행이면 true를 반환한다. */
    boolean save(ForecastPublication publication);
}
