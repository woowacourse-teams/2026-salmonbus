package com.gustler.backend.forecasting.domain.publication;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Lost;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.NotArrivedYet;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Settled;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabelResolver;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TripArrivalLabelTest {
    private static final Instant AT = Instant.parse("2026-09-21T00:00:00Z");
    private final PendingForecast forecast = new PendingForecast(1, 3, 1, "bus", 1, AT, AT, 10L);

    private ArrivalCandidate candidate(Long trip, final boolean assessed) {
        return new ArrivalCandidate(2, new ObservedVehicle("bus", 1, 3, AT.plusSeconds(10), 40, null), trip, assessed);
    }

    @Test
    void 예측과_도착_관측의_편도_ID가_다르면_Lost를_반환한다() {
        // given
        List<ArrivalCandidate> arrivals = List.of(candidate(20L, true));
        Instant evaluatedAt = AT.plusSeconds(20);

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(forecast, arrivals, evaluatedAt);

        // then
        assertThat(actual).isInstanceOf(Lost.class);
    }

    @Test
    void 도착_관측의_편도_판정이_끝나지_않으면_NotArrivedYet을_반환한다() {
        // given
        List<ArrivalCandidate> arrivals = List.of(candidate(null, false));
        Instant evaluatedAt = AT.plusSeconds(20);

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(forecast, arrivals, evaluatedAt);

        // then
        assertThat(actual).isInstanceOf(NotArrivedYet.class);
    }

    @Test
    void 편도_판정이_끝났지만_사용_가능한_편도_ID가_없으면_Lost를_반환한다() {
        // given
        List<ArrivalCandidate> arrivals = List.of(candidate(null, true));
        Instant evaluatedAt = AT.plusSeconds(20);

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(forecast, arrivals, evaluatedAt);

        // then
        assertThat(actual).isInstanceOf(Lost.class);
    }

    @Test
    void 예측과_도착_관측의_편도_ID가_같으면_관측_ID와_잔여석으로_Settled를_반환한다() {
        // given
        List<ArrivalCandidate> arrivals = List.of(candidate(10L, true));
        Instant evaluatedAt = AT.plusSeconds(20);

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(forecast, arrivals, evaluatedAt);

        // then
        assertThat(actual).isEqualTo(new Settled(2, 40));
    }
}
