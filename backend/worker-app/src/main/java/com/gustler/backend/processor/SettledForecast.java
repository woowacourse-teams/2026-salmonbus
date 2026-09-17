package com.gustler.backend.processor;

/** 잔여석을 아는 채로 닫힌 예보 한 줄. 당일 성적 집계에 더할 재료다. */
public record SettledForecast(
    long routeVersionId,
    int stopsToTarget,
    double rawFullChance,
    long arrivalObservationId,
    int seatsOnArrival
) {

    public boolean wasFull() {
        return seatsOnArrival == 0;
    }
}
