package com.gustler.backend.forecasting.application.evaluation;

/** 목표가 다른 Les 평가를 기존 수요 통계와 분리해서 처리한다. */
public interface LesForecastEvaluation {
    void settle();
}
