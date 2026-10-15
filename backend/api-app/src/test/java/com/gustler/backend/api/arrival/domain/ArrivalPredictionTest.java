package com.gustler.backend.api.arrival.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ArrivalPredictionTest {
    private static final Instant RECEIVED = Instant.parse("2026-10-14T03:00:00Z");

    @Test
    void 초_정보가_없으면_분_정보를_수신시각에_더한다() {
        var source = new Prediction(null, 1, null, 3, null, null, "NOT_PROVIDED", null, null, null);
        var prediction = ArrivalPrediction.at(source, RECEIVED, RECEIVED.plusSeconds(10));
        assertThat(prediction.resolution()).isEqualTo("MINUTES");
        assertThat(prediction.expectedArrivalAt()).isEqualTo(RECEIVED.plusSeconds(180));
        assertThat(prediction.remainingSeconds()).isEqualTo(170);
    }

    @Test
    void 예상시각이_지났으면_실제_도착으로_취급하지_않는다() {
        var source = new Prediction("vehicle-1", 1, 30, 1, null, 0, null, null, null, null);
        var prediction = ArrivalPrediction.at(source, RECEIVED, RECEIVED.plusSeconds(31));
        assertThat(prediction.available()).isFalse();
        assertThat(prediction.remainingSeconds()).isNull();
        assertThat(prediction.expectedArrivalAt()).isEqualTo(RECEIVED.plusSeconds(30));
    }

    @Test
    void 초_정보를_우선하고_남은_시간은_올림한다() {
        var source = new Prediction("vehicle-1", 1, 30, 1, null, 0, null, null, null, null);
        var prediction = ArrivalPrediction.at(source, RECEIVED, RECEIVED.plusMillis(29_500));
        assertThat(prediction.remainingSeconds()).isEqualTo(1);
        assertThat(prediction.resolution()).isEqualTo("SECONDS");
        assertThat(ArrivalPrediction.at(source, RECEIVED, RECEIVED.plusSeconds(30)).remainingSeconds()).isZero();
    }
}
