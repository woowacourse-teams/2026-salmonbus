package com.gustler.backend.processor;

/** 집계가 낸 셀 하나. 어느 시간대의 것인지까지 담는다. */
public record StopDemandMeasurement(
    TimeSlot timeSlot,
    StopDemandCell cell
) {
}
