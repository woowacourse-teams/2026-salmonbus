package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.api.forecast.ForecastBatchEvents;

/** 커밋한 배치의 입력을 복구 통과, 수집 사실, 현재 통과, 예보 순서로 정산에 전달한다. */
@FunctionalInterface
public interface ForecastEvents {

    /** 큐에 등록하지 못한 배치의 정산 입력은 저장된 기록에서 복구한다. */
    void committed(ForecastBatchEvents batch);
}
