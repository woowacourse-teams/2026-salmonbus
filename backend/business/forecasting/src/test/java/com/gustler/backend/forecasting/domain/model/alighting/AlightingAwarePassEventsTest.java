package com.gustler.backend.forecasting.domain.model.alighting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlightingAwarePassEventsTest {
    @Test
    void 다음_실제_정류장_관측_전에는_마지막_출발석을_확정하지_않는다() {
        assertThat(
            AlightingAwarePassEvents.resolve(List.of(row(1, 0, 1, 5), row(2, 20, 2, 3), row(3, 40, 2, 1)), List.of(1, 2, 3)))
            .isEmpty();
    }

    @Test
    void 첫_출발과_마지막_출발과_확정시각을_구분한다() {
        AlightingAwarePassEvent event = AlightingAwarePassEvents
            .resolve(List.of(row(1, 0, 1, 5), row(2, 20, 2, 3), row(3, 40, 2, 1), row(4, 60, 3, 1)), List.of(1, 2, 3))
            .getFirst();
        assertThat(event.firstDeparture().observationId()).isEqualTo(2);
        assertThat(event.departure().observationId()).isEqualTo(3);
        assertThat(event.confirmation().observationId()).isEqualTo(4);
        assertThat(event.confirmedAt()).isEqualTo(row(4, 60, 3, 1).receivedAt());
    }

    @Test
    void 관측_공백을_넘어_다른_구간과_합치지_않는다() {
        assertThat(
            AlightingAwarePassEvents.resolve(List.of(row(1, 0, 1, 5), row(2, 20, 2, 3), row(3, 120, 3, 1)), List.of(1, 2, 3)))
            .isEmpty();
        // 원본의 >90초와 >1800초 경계는 마이크로초를 버리지 않는다.
        assertThat(
            AlightingAwarePassEvents.resolve(List.of(row(1, 0, 1, 5), row(2, 90, 2, 3), row(3, 100, 3, 1)), List.of(1, 2, 3)))
            .hasSize(1);
        assertThat(AlightingAwarePassEvents.resolve(List.of(row(1, 0, 1, 5),
            row(2, Instant.EPOCH.plusSeconds(90).plusNanos(1000), 2, 3), row(3, 100, 3, 1)), List.of(1, 2, 3)))
            .isEmpty();
        AlightingAwarePassEvent sameTrip = AlightingAwarePassEvents.resolve(List.of(row(1, 0, 1, 5), row(2, 1800, 1, 5),
            row(3, 1820, 2, 3), row(4, 1840, 3, 1)), List.of(1, 2, 3)).getFirst();
        assertThat(sameTrip.tripId()).isEqualTo(1);
        AlightingAwarePassEvent nextTrip = AlightingAwarePassEvents.resolve(List.of(row(1, 0, 1, 5),
            row(2, Instant.EPOCH.plusSeconds(1800).plusNanos(1000), 1, 5),
            row(3, 1820, 2, 3), row(4, 1840, 3, 1)), List.of(1, 2, 3)).getFirst();
        assertThat(nextTrip.tripId()).isEqualTo(2);
    }

    @Test
    void 다른_차량은_연결하지_않는다() {
        AlightingAwareObservation other = new AlightingAwareObservation(4, 4, 4, Instant.EPOCH.plusSeconds(60), "other", 3, 3, 2, 1, 0);
        assertThat(AlightingAwarePassEvents.resolve(List.of(row(1, 0, 1, 5), row(2, 20, 2, 3), other), List.of(1, 2, 3)))
            .isEmpty();
    }

    private AlightingAwareObservation row(
        final long id,
        final int second,
        final int order,
        final int seats
    ) {
        return row(id, Instant.EPOCH.plusSeconds(second), order, seats);
    }

    private AlightingAwareObservation row(
        final long id,
        Instant at,
        final int order,
        final int seats
    ) {
        return new AlightingAwareObservation(id, id, id, at, "bus", order, order, 2, seats, 0);
    }
}
