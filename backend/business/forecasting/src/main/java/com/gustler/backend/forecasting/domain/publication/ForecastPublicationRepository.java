package com.gustler.backend.forecasting.domain.publication;

public interface ForecastPublicationRepository {

    /** 발행, 예측과 최초 평가 상태를 같은 트랜잭션에 저장한다. 같은 배치를 다시 발행하면 예측 값을 덮어쓴다. */
    void save(ForecastPublication publication);
}
