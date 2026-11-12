package com.gustler.backend.api.push.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.*;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.api.board.application.BoardOverview;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.domain.*;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationMessageResolverTest {
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    private static final FavoriteTarget TARGET = new FavoriteTarget("234000884", "203000165", FavoriteDirection.UP);
    private final BoardQueryService boards = mock(BoardQueryService.class);
    private final ArrivalQueryService arrivals = mock(ArrivalQueryService.class);
    private final NotificationMessageResolver resolver = new NotificationMessageResolver(
        boards, arrivals, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 같은_차량의_도착시간과_예상좌석을_두_줄로_보낸다() {
        board(new VehicleForecast.Available(0.7, 42.9), NOW.plusSeconds(60));
        arrivals("vehicle", 3);
        assertThat(resolver.apply(TARGET)).isEqualTo(new NotificationMessage("3330번 · 판교역",
            "약 5분 후 도착 · 탑승확률 높음\n도착 시 빈 좌석 42석 예상"));
    }

    @Test
    void 다른_차량이나_다른_방문의_도착시간은_연결하지_않는다() {
        board(new VehicleForecast.Available(0, 0.0), NOW.plusSeconds(60));
        arrivals("other", 3);
        assertThat(resolver.apply(TARGET).body()).isEqualTo(
            "도착 정보 없음 · 탑승확률 매우 낮음\n도착 시 빈 좌석 0석 예상");
        arrivals("vehicle", 4);
        assertThat(resolver.apply(TARGET).body()).startsWith("도착 정보 없음");
    }

    @Test
    void 조회실패나_만료된_예보를_숫자로_보내지_않는다() {
        board(new VehicleForecast.Available(1, 42.0), NOW);
        assertThat(resolver.apply(TARGET).body()).doesNotContain("42", "높음");
        when(boards.getBoard(any())).thenThrow(new IllegalStateException());
        assertThat(resolver.apply(TARGET)).isEqualTo(NotificationMessage.unavailable());
    }

    @Test
    void 예보가_없어도_도착시간을_보내고_좌석은_정보없음으로_표시한다() {
        board(new VehicleForecast.Unavailable(), NOW.plusSeconds(60));
        arrivals("vehicle", 3);
        assertThat(resolver.apply(TARGET).body()).isEqualTo(
            "약 5분 후 도착 · 탑승확률 정보 없음\n예상 빈 좌석 정보 없음");
        when(arrivals.get(any())).thenThrow(new IllegalStateException());
        assertThat(resolver.apply(TARGET).body()).startsWith("도착 정보 없음");
    }

    private void board(VehicleForecast forecast, Instant staleAt) {
        var route = new BoardRoute("234000884", "3330", "기점", "종점", RouteStatus.FORECAST_READY, null,
            List.of(), "1", RouteHeadways.none());
        var stop = new StopState(3, "203000165", "판교역", BoardDirection.UP, true,
            List.of(new ApproachingVehicle("vehicle", 2, forecast)));
        var board = new Board(route, NOW.atOffset(ZoneOffset.UTC), staleAt.atOffset(ZoneOffset.UTC),
            new ForecastModel(1, "test", NOW.atOffset(ZoneOffset.UTC)), 1, List.of(stop));
        when(boards.getBoard(any())).thenReturn(new BoardOverview(board, Duration.ZERO));
    }

    private void arrivals(String vehicle, int sequence) {
        var prediction = ArrivalPrediction.at(new Prediction(vehicle, 1, 300, null, 2, 1,
            null, null, null, null), NOW, NOW);
        var visit = new ArrivalVisit("234000884", "234000884", 1, sequence, "UP", NOW.minusSeconds(60));
        var route = new RouteArrivals("234000884", "3330", visit, sequence, List.of(prediction));
        when(arrivals.get(any())).thenReturn(new StationArrivalsOverview("203000165", NOW,
            ArrivalAvailability.READY, null, null, null, List.of(route), Duration.ZERO));
    }
}
