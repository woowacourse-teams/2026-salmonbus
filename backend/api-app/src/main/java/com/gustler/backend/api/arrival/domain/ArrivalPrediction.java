package com.gustler.backend.api.arrival.domain;

import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import java.time.Duration;
import java.time.Instant;

/** 원본 예측을 수신시각 기준으로 해석한 값. 실제 도착이나 차량 좌표를 뜻하지 않는다. */
public record ArrivalPrediction(Prediction source, Instant estimateBaseAt,
                                Instant expectedArrivalAt, Long remainingSeconds) {
    public static ArrivalPrediction at(Prediction source, Instant receivedAt, Instant now) {
        long seconds = source.sourceRemainingSeconds() != null
            ? source.sourceRemainingSeconds()
            : Math.multiplyExact(source.sourceRemainingMinutes().longValue(), 60);
        Instant expected = receivedAt.plusSeconds(seconds);
        Long remaining = expected.isBefore(now) ? null
            : Math.max(0, (Duration.between(now, expected).toMillis() + 999) / 1000);
        return new ArrivalPrediction(source, receivedAt, expected, remaining);
    }

    public boolean available() {
        return remainingSeconds != null;
    }

    public String resolution() {
        return source.sourceRemainingSeconds() == null ? "MINUTES" : "SECONDS";
    }
}
