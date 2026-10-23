package com.gustler.backend.forecasting.domain.quality;

import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Observation;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Previous;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Start;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Status;
import java.time.Instant;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;

class OneWayTripAssessmentTest {
    @Test
    void 기존_편도에서_범위_밖_좌석이_발견되면_편도_전체를_제외하고_다음_관측에도_제외를_이어간다() {
        // given
        Instant at = Instant.parse("2026-09-23T00:00:00Z");
        Route route = new Route(1, 7, 4, null);
        Start start = new Start(1, 2);
        Previous previous = new Previous(new Observation(2, "bus", at, 2, 0, 44), 1, Status.ELIGIBLE, start);
        Observation anomaly = new Observation(3, "bus", at.plusSeconds(30), 3, 0, 71);

        // when
        OneWayTripAssessment excluded = OneWayTripAssessment.assess(route, previous, anomaly);
        OneWayTripAssessment following = OneWayTripAssessment.assess(route, excluded.asPrevious(),
            new Observation(4, "bus", at.plusSeconds(60), 4, 1, 44));

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(excluded.startsTrip()).isFalse();
            softly.assertThat(excluded.excludesExistingTrip()).isTrue();
            softly.assertThat(excluded.asPrevious().start()).isEqualTo(start);
            softly.assertThat(following.excludesExistingTrip()).isFalse();
            softly.assertThat(following.decision().tripId()).isEqualTo(1);
            softly.assertThat(following.decision().status()).isEqualTo(Status.EXCLUDED);
        }
    }
}
