package com.gustler.backend.forecasting.api.pipeline;

/**
 * 배치 하나를 예보 단계가 처리한 결과다. 값의 이름은 {@code forecast_run.outcome}에 그대로 저장된다.
 */
public enum ForecastOutcome {
    /** 버스 한 대 이상의 예측을 냈다. */
    PUBLISHED,
    /** 처리했지만 예측할 버스가 없었다. */
    EMPTY,
    /** 수신 뒤 처리 시작까지 허용 시간을 넘겨 예보하지 않았다. */
    LATE,
    /** 모델이 지원하지 않는 노선·시간이라 예보하지 않았다. */
    OUT_OF_SCOPE,
    /** 모델 입력을 만들 수 없어 예보하지 않았다. */
    INPUT_UNAVAILABLE;

    /** 이 결과가 버스별 예측을 싣는지 돌려준다. {@link #PUBLISHED}만 싣는다. */
    public boolean carriesForecasts() {
        return this == PUBLISHED;
    }
}
