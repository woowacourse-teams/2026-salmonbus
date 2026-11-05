package com.gustler.backend.forecasting.domain.model.les;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LesConfirmedObservationTest {
    @ParameterizedTest
    @CsvSource({
            "1,15,15,1,false,false,false",
            "0,15,15,1,false,false,false",
            "-1,15,15,1,false,true,true",
            "1,91,15,1,false,true,false",
            "1,15,91,1,false,true,false",
            "1,15,-1,1,false,true,false",
            "1,15,15,2,false,true,true",
            "1,15,15,1,true,true,true",
            "1,1801,15,1,false,true,true"
    })
    void 확인_수신_간격과_진행_경계를_분리한다(
        final int passedDifference,
        final int confirmedSeconds,
        final int physicalSeconds,
        final long version,
        final boolean down,
        final boolean chainBreak,
        final boolean tripBreak) {
        // given
        Instant start = Instant.parse("2026-11-04T00:00:00Z");
        LesObservation previous = new LesObservation(1, 1, 0, start, "bus", 2, 3, 1, 40, 0,
            1, start.minusSeconds(30), false);
        LesObservation current = new LesObservation(2, 2, 1, start.plusSeconds(confirmedSeconds), "bus",
            2 + passedDifference, 4, 1, 0, 0, version, previous.sourceReceivedAt().plusSeconds(physicalSeconds), down);

        // then
        assertSoftly(softly -> {
            softly.assertThat(current.startsConfirmedChainAfter(previous)).isEqualTo(chainBreak);
            softly.assertThat(current.startsConfirmedTripAfter(previous)).isEqualTo(tripBreak);
            LesVehiclePrefix state = LesHistoryCompactor.advance(LesHistoryCompactor.advance(null, previous), current);
            softly.assertThat(state.capacity()).isEqualTo(40);
            softly.assertThat(state.capacityObservation()).isEqualTo(previous);
        });
    }
}
