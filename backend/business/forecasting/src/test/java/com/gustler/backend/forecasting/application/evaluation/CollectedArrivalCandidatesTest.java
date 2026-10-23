package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Kind;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Row;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CollectedArrivalCandidatesTest {
    private static final Instant T = Instant.parse("2026-10-22T00:00:00Z");
    private static final int TURN = 10;

    @ParameterizedTest
    @CsvSource({"0, 1, 0", "1, 0, 1", "2, 1, 0"})
    void 기점에서는_정류소_도착만_이전_편도_방향이다(final int runningState, final int passedStopOrder, final long direction) {
        // given
        Row row = new Row(1, "bus", 40, passedStopOrder, null, 1, runningState);
        ObservationDeliveryBatch batch = new ObservationDeliveryBatch(1, 1, 1, Kind.STORED, T, List.of(row));

        // when
        List<ArrivalCandidate> actual = CollectedArrivalCandidates.from(batch, TURN, T);

        // then
        assertThat(actual).singleElement()
            .extracting(ArrivalCandidate::qualityDirection)
            .isEqualTo(direction);
    }
}
