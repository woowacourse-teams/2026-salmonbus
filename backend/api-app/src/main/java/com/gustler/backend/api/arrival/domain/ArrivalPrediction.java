package com.gustler.backend.api.arrival.domain;

import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import java.time.Duration;
import java.time.Instant;

/** 원본 예측을 수신시각 기준으로 해석한 값. 실제 도착이나 차량 좌표를 뜻하지 않는다. */
public record ArrivalPrediction(Prediction source, Instant estimateBaseAt,
    Instant expectedArrivalAt, Long remainingSeconds) {
    /**
     * 수신 시각에 원천 잔여 초를 더해 예상 도착 시각을 고정하고 현재 시각 기준 잔여 초를 계산한다.
     */
    public static ArrivalPrediction at(Prediction source, Instant receivedAt, Instant now) {
        final long seconds = sourceRemainingSeconds(source);
        Instant expected = receivedAt.plusSeconds(seconds);
        Long remaining = remainingSeconds(expected, now);
        return new ArrivalPrediction(source, receivedAt, expected, remaining);
    }

    private static long sourceRemainingSeconds(Prediction source) {
        if (source.sourceRemainingSeconds() != null) {
            return source.sourceRemainingSeconds();
        }
        return Math.multiplyExact(source.sourceRemainingMinutes().longValue(), 60);
    }

    /** 예상 시각이 지났으면 부재로 남기고, 유효한 잔여 밀리초는 초 단위로 올림한다. */
    private static Long remainingSeconds(Instant expected, Instant now) {
        if (expected.isBefore(now)) {
            return null;
        }
        return Math.max(0, (Duration.between(now, expected).toMillis() + 999) / 1000);
    }

    public boolean available() {
        return remainingSeconds != null;
    }

    public String resolution() {
        if (source.sourceRemainingSeconds() == null) {
            return "MINUTES";
        }
        return "SECONDS";
    }
}
