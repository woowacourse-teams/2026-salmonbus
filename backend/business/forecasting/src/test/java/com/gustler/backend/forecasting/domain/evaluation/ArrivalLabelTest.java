package com.gustler.backend.forecasting.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArrivalLabelTest {

    private static final long ARRIVAL_OBSERVATION_ID = 7L;
    private static final int SEATS_ON_ARRIVAL = 12;

    @Test
    void 아직_도착하지_않은_판정으로는_평가를_확정하지_않는다() {
        // given
        ArrivalLabel actual = new ArrivalLabel.NotArrivedYet();

        // then
        assertThat(actual.scoringState()).isEqualTo(ScoringState.PENDING);
        assertThat(actual.settles()).isFalse();
    }

    @Test
    void 도착을_판정한_결과로는_평가를_확정한다() {
        // given
        List<ArrivalLabel> settling = List.of(
            new ArrivalLabel.Settled(ARRIVAL_OBSERVATION_ID, SEATS_ON_ARRIVAL),
            new ArrivalLabel.SeatMissing(ARRIVAL_OBSERVATION_ID),
            new ArrivalLabel.Skipped(),
            new ArrivalLabel.Lost());

        // then
        assertThat(settling).allSatisfy(label -> {
            assertThat(label.scoringState()).isNotEqualTo(ScoringState.PENDING);
            assertThat(label.settles()).isTrue();
        });
    }
}
