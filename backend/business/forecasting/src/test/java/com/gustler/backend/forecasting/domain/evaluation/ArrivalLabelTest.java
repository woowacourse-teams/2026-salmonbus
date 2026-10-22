package com.gustler.backend.forecasting.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Lost;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.NotArrivedYet;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.SeatMissing;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Settled;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Skipped;
import java.util.List;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;

class ArrivalLabelTest {
    private static final long ARRIVAL_OBSERVATION_ID = 7L;
    private static final int SEATS_ON_ARRIVAL = 12;

    @Test
    void 아직_도착하지_않은_판정으로는_평가를_확정하지_않는다() {
        // when
        ArrivalLabel actual = new NotArrivedYet();

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(actual.scoringState()).isEqualTo(ScoringState.PENDING);
            softly.assertThat(actual.settles()).isFalse();
        }
    }

    @Test
    void 도착을_판정한_결과로는_평가를_확정한다() {
        // when
        List<ArrivalLabel> settling = List.of(
            new Settled(ARRIVAL_OBSERVATION_ID, SEATS_ON_ARRIVAL),
            new SeatMissing(ARRIVAL_OBSERVATION_ID),
            new Skipped(),
            new Lost());

        // then
        assertThat(settling).allSatisfy(label -> {
            assertThat(label.scoringState()).isNotEqualTo(ScoringState.PENDING);
            assertThat(label.settles()).isTrue();
        });
    }
}
