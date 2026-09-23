package com.gustler.backend.forecasting.application.quality;

import java.util.List;

/** 조사를 재개하거나 판정 근거로 다시 읽을 관측의 원본을 확정한다. */
public interface QualityInputRetention {
    void confirmObservations(List<Long> observationIds);
}
