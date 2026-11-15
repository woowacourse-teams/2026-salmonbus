package com.gustler.backend.forecasting.application.pipeline;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class HeldBatchesTest {
    private static final Instant T0 = Instant.parse("2026-10-14T01:00:00Z");

    @Test
    void 기다리는_배치는_넣은_순서와_무관하게_먼저_수신한_것부터_꺼낸다() {
        // given
        HeldBatches held = new HeldBatches();

        // when
        held.add(new HeldBatches.Held(12, 7, T0.plusSeconds(150)));
        held.add(new HeldBatches.Held(11, 7, T0.plusSeconds(120)));
        held.add(new HeldBatches.Held(10, 7, T0.plusSeconds(90)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(held.head().observationBatchId()).isEqualTo(10);
            softly.assertThat(held.waitsBehindOther(10)).isFalse();
            softly.assertThat(held.waitsBehindOther(11)).isTrue();
            softly.assertThat(held.waitsBehindOther(99)).isFalse();
            softly.assertThat(held.size()).isEqualTo(3);
        });
    }

    @Test
    void 이미_기다리는_배치를_다시_넣어도_처음_정한_기한을_바꾸지_않는다() {
        // given
        HeldBatches held = new HeldBatches();
        held.add(new HeldBatches.Held(10, 7, T0.plusSeconds(90)));

        // when
        held.add(new HeldBatches.Held(10, 7, T0.plusSeconds(600)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(held.size()).isOne();
            softly.assertThat(held.head().releaseAt()).isEqualTo(T0.plusSeconds(90));
        });
    }
}
