package com.gustler.backend.api.board.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.route.domain.RouteStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardTest {

    private static final OffsetDateTime OBSERVED_AT = OffsetDateTime.parse("2026-09-30T08:00:00+09:00");
    private static final ForecastModel ACTIVE_MODEL = new ForecastModel(
        9L,
        "model-active",
        OffsetDateTime.parse("2026-09-29T23:59:59+09:00")
    );

    @Test
    void 예보를_준비_중인_노선의_보드에는_좌석_예보를_담을_수_없다() {
        // given
        final List<StopState> stops = stopsApproachedBy(new VehicleForecast.Available(0.8, 12.0));

        // when & then
        assertThatThrownBy(() -> board(RouteStatus.PREPARING, stops))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 예보를_준비_중인_노선의_보드는_예보_없는_차량을_담는다() {
        // given
        final List<StopState> stops = stopsApproachedBy(new VehicleForecast.Unavailable());

        // when
        final Board actual = board(RouteStatus.PREPARING, stops);

        // then
        assertThat(actual.stops().getFirst().approachingVehicles())
            .extracting(ApproachingVehicle::forecast)
            .containsExactly(new VehicleForecast.Unavailable());
    }

    @Test
    void 예보가_준비된_노선의_보드는_좌석_예보를_담는다() {
        // given
        final List<StopState> stops = stopsApproachedBy(new VehicleForecast.Available(0.8, 12.0));

        // when
        final Board actual = board(RouteStatus.FORECAST_READY, stops);

        // then
        assertThat(actual.stops().getFirst().approachingVehicles())
            .extracting(ApproachingVehicle::forecast)
            .containsExactly(new VehicleForecast.Available(0.8, 12.0));
    }

    private Board board(
        RouteStatus status,
        List<StopState> stops
    ) {
        return new Board(
            new BoardRoute(
                "204000070",
                "9007",
                "기점",
                "종점",
                status,
                null,
                List.of(new DirectionInfo(BoardDirection.UP, "종점 방면", "기점", "종점", "05:00", "23:00")),
                "31"
            ),
            OBSERVED_AT,
            OBSERVED_AT.plusMinutes(5),
            ACTIVE_MODEL,
            1,
            stops
        );
    }

    private List<StopState> stopsApproachedBy(
        VehicleForecast forecast
    ) {
        return List.of(new StopState(
            2,
            "STOP-2",
            "종점",
            BoardDirection.UP,
            true,
            List.of(new ApproachingVehicle("A", 1, forecast))
        ));
    }
}
