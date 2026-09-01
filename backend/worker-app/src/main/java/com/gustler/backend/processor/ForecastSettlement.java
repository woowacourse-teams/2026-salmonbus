package com.gustler.backend.processor;

import java.time.Instant;

/** 예보 한 줄에 채워 넣을 회수 결과. 아직 안 닿은 예보는 쓸 것이 없어 담지 않는다. */
public record ForecastSettlement(
    long vehicleObservationId,
    int targetStopOrder,
    ArrivalLabel label,
    Instant scoredAt
) {

    public ForecastSettlement {
        if (label instanceof ArrivalLabel.NotArrivedYet) {
            throw new IllegalArgumentException("아직 대상 정류장에 안 닿은 예보는 열어 둔다");
        }
        if (scoredAt == null) {
            throw new IllegalArgumentException("회수한 예보에는 회수 시각이 있어야 한다");
        }
    }
}
