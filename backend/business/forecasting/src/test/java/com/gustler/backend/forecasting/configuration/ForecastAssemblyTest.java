package com.gustler.backend.forecasting.configuration;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcReplaySource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ForecastAssemblyTest {
    @Test
    void worker가_쓰는_되감기_창과_줄_한도와_출퇴근_범위_제한은_설정_키로_두던_때의_기본값과_같다() {
        // given
        ForecastPolicy policy = new ForecastPolicy(Duration.ofMinutes(10), 20);

        // when
        ForecastAssembly.Settings actual = ForecastAssembly.Settings.of(policy);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.policy()).isEqualTo(policy);
            softly.assertThat(actual.replayWindow()).isEqualTo(Duration.ofHours(6));
            softly.assertThat(actual.replayExtendedWindow()).isEqualTo(Duration.ofHours(12));
            softly.assertThat(actual.rushOnly()).isTrue();
            softly.assertThat(JdbcReplaySource.DEFAULT_MAXIMUM_ROWS).isEqualTo(250_000);
            softly.assertThat(ScoringStage.DEFAULT_OBSERVATION_LIMIT).isEqualTo(150_000);
        });
    }
}
