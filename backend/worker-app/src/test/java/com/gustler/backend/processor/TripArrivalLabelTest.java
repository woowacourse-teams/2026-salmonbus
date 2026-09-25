package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TripArrivalLabelTest {
    private static final Instant AT = Instant.parse("2026-09-21T00:00:00Z");
    private final PendingForecast forecast = new PendingForecast(1, 3, 1, "bus", 1, AT, AT, 10L);

    private ArrivalCandidate candidate(Long trip, boolean assessed) {
        return new ArrivalCandidate(2, new ObservedVehicle("bus", 1, 3, AT.plusSeconds(10), 40, null), trip, assessed);
    }

    @Test
    void 예측과_도착_관측의_편도_ID가_다르면_Lost를_반환한다() {
        // given
        var arrivals = List.of(candidate(20L, true));
        var evaluatedAt = AT.plusSeconds(20);

        // when
        var actual = ArrivalLabelResolver.resolve(forecast, arrivals, evaluatedAt);

        // then
        assertThat(actual).isInstanceOf(ArrivalLabel.Lost.class);
    }

    @Test
    void 도착_관측의_편도_판정이_끝나지_않으면_NotArrivedYet을_반환한다() {
        // given
        var arrivals = List.of(candidate(null, false));
        var evaluatedAt = AT.plusSeconds(20);

        // when
        var actual = ArrivalLabelResolver.resolve(forecast, arrivals, evaluatedAt);

        // then
        assertThat(actual).isInstanceOf(ArrivalLabel.NotArrivedYet.class);
    }

    @Test
    void 편도_판정이_끝났지만_사용_가능한_편도_ID가_없으면_Lost를_반환한다() {
        // given
        var arrivals = List.of(candidate(null, true));
        var evaluatedAt = AT.plusSeconds(20);

        // when
        var actual = ArrivalLabelResolver.resolve(forecast, arrivals, evaluatedAt);

        // then
        assertThat(actual).isInstanceOf(ArrivalLabel.Lost.class);
    }

    @Test
    void 예측과_도착_관측의_편도_ID가_같으면_관측_ID와_잔여석으로_Settled를_반환한다() {
        // given
        var arrivals = List.of(candidate(10L, true));
        var evaluatedAt = AT.plusSeconds(20);

        // when
        var actual = ArrivalLabelResolver.resolve(forecast, arrivals, evaluatedAt);

        // then
        assertThat(actual).isEqualTo(new ArrivalLabel.Settled(2, 40));
    }
}
