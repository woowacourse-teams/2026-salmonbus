package com.gustler.backend.forecasting.domain.model.alighting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlightingAwareHistoryRetentionTest {

    private static final Instant AS_OF = Instant.parse("2026-10-15T00:00:00Z");
    private static final Instant WINDOW_START = AS_OF.minusSeconds(5400);
    private static final long ACTIVE_TRIP = 17;

    @Test
    void 복구할_운행은_차량별로_창_직전_다섯_건을_남긴다() {
        // given
        List<AlightingAwarePassEvent> events = new ArrayList<>();
        for (int stop = 6; stop >= 1; stop--) {
            events.add(pass(
                    "bus",
                    ACTIVE_TRIP,
                    stop,
                    WINDOW_START.minusSeconds(7 - stop)
            ));
        }
        AlightingAwarePassEvent anotherVehicle = pass(
                "other",
                ACTIVE_TRIP,
                1,
                WINDOW_START.minusSeconds(7)
        );
        events.add(anotherVehicle);
        events.add(pass(
                "finished",
                99,
                1,
                WINDOW_START.minusSeconds(1)
        ));
        AlightingAwarePassEvent atWindowStart = pass(
                "finished",
                99,
                2,
                WINDOW_START
        );
        events.add(atWindowStart);

        // when
        List<AlightingAwarePassEvent> retained = AlightingAwareHistoryCompactor.compact(history(events), AS_OF)
            .events();

        // then
        assertThat(retained).containsExactly(
                anotherVehicle,
                events.get(4),
                events.get(3),
                events.get(2),
                events.get(1),
                events.getFirst(),
                atWindowStart
        );
    }

    @Test
    void 중복_통과는_마지막_값을_쓰고_출발_시각이_같으면_첫_등록_순서를_유지한다() {
        // given
        AlightingAwarePassEvent first = pass(
                "bus",
                ACTIVE_TRIP,
                4,
                AS_OF
        );
        AlightingAwarePassEvent second = pass(
                "bus",
                ACTIVE_TRIP,
                5,
                AS_OF
        );
        AlightingAwarePassEvent replaced = pass(
                "bus",
                ACTIVE_TRIP,
                4,
                WINDOW_START
        );
        AlightingAwarePassEvent insideWindow = pass(
                "bus",
                ACTIVE_TRIP,
                3,
                AS_OF
        );
        AlightingAwarePassEvent beforeWindow = pass(
                "bus",
                ACTIVE_TRIP,
                3,
                WINDOW_START.minusSeconds(1)
        );
        AlightingAwareHistoryCompactor.History history =
            history(List.of(
                    replaced,
                    second,
                    first,
                    beforeWindow,
                    insideWindow
            ));

        // when
        List<AlightingAwarePassEvent> retained = AlightingAwareHistoryCompactor.compact(history, AS_OF).events();

        // then
        assertThat(retained).containsExactly(beforeWindow, first, second);
    }

    private static AlightingAwareHistoryCompactor.History history(List<AlightingAwarePassEvent> events) {
        AlightingAwareObservation active = observation("bus", ACTIVE_TRIP, AS_OF);
        return new AlightingAwareHistoryCompactor.History(Map.of(), List.of(active), events);
    }

    private static AlightingAwarePassEvent pass(
        String vehicleId,
        final long tripId,
        final int stop,
        Instant departureTime
    ) {
        AlightingAwareObservation departure = observation(vehicleId, stop, departureTime);
        return new AlightingAwarePassEvent(
                vehicleId,
                tripId,
                stop,
                departure,
                departure,
                departureTime
        );
    }

    private static AlightingAwareObservation observation(String vehicleId, final long observationId, Instant at) {
        return new AlightingAwareObservation(
                observationId,
                observationId,
                observationId,
                at,
                vehicleId,
                1,
                1,
                2,
                20,
                0
        );
    }
}
