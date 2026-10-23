package com.gustler.backend.forecasting.domain.statistics;

/** 집계 결과인 셀 하나와 해당 시간대를 담는다. */
public record StopDemandMeasurement(
    TimeSlot timeSlot,
    StopDemandCell cell
) {
}
