package com.gustler.backend.api.station.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.DirectionInfo;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.route.domain.RouteStatus;
import com.gustler.backend.api.station.domain.ScheduleWindow;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalmonRouteRowsTest {

    private static final String ROUTE_ID = "234000882";
    private static final int STOP_SEQUENCE = 23;
    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    private static final LocalTime NOON = LocalTime.NOON;
    private static final ArrivalVisit VISIT = new ArrivalVisit(
        ROUTE_ID, ROUTE_ID, 4, STOP_SEQUENCE, "UP", NOW.minusSeconds(3600)
    );

    @Test
    void 판정보드_차량에_같은_차량의_GBIS_ETA만_붙이고_남은_정류장은_판정보드_값을_쓴다() {
        // given
        Board board = board(new ApproachingVehicle("V1", 1, available(0.9)));
        RouteArrivals gbis = gbis(prediction("V1", 1, 40, 2));

        // when
        StationDetailOverview.RouteView row = SalmonRouteRows.of(VISIT, board, null, gbis, true, null, NOON);

        // then
        assertThat(row.arrivals()).hasSize(1);
        StationDetailOverview.VehicleView vehicle = row.arrivals().getFirst();
        assertThat(vehicle.prediction()).isNotNull();
        assertThat(vehicle.forecast()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(vehicle.remainingStops()).isEqualTo(1);
            softly.assertThat(vehicle.positionSource()).isEqualTo(StationDetailOverview.PositionSource.BOARD);
            softly.assertThat(vehicle.prediction().remainingSeconds()).isEqualTo(40);
            softly.assertThat(vehicle.etaReason()).isNull();
            softly.assertThat(vehicle.forecast().status()).isEqualTo("AVAILABLE");
            softly.assertThat(vehicle.forecast().seatAvailableProbability()).isEqualTo(0.9);
            softly.assertThat(row.status()).isEqualTo("READY");
        });
    }

    @Test
    void GBIS_수신본이_없어도_판정보드_차량과_예보를_낸다() {
        // given
        Board board = board(new ApproachingVehicle("V1", 3, available(0.4)));

        // when
        StationDetailOverview.RouteView row = SalmonRouteRows.of(VISIT, board, null, null, false, null, NOON);

        // then
        assertThat(row.arrivals()).singleElement().satisfies(vehicle -> {
            assertThat(vehicle.forecast()).isNotNull();
            assertSoftly(softly -> {
                softly.assertThat(vehicle.positionSource()).isEqualTo(StationDetailOverview.PositionSource.BOARD);
                softly.assertThat(vehicle.prediction()).isNull();
                softly.assertThat(vehicle.etaReason()).isEqualTo(SalmonRouteRows.ARRIVAL_UNAVAILABLE);
                softly.assertThat(vehicle.forecast().status()).isEqualTo("AVAILABLE");
            });
        });
        assertThat(row.status()).isEqualTo("READY");
    }

    @Test
    void GBIS_수신본에_같은_차량이_없으면_VEHICLE_NOT_MATCHED다() {
        // given
        Board board = board(new ApproachingVehicle("V1", 3, available(0.4)));
        RouteArrivals gbis = gbis(prediction("OTHER", 1, 300, 6));

        // when
        StationDetailOverview.RouteView row = SalmonRouteRows.of(VISIT, board, null, gbis, true, null, NOON);

        // then
        assertThat(row.arrivals()).hasSize(2);
        assertSoftly(softly -> {
            softly.assertThat(row.arrivals()).extracting(StationDetailOverview.VehicleView::vehicleId)
                .containsExactly("V1", "OTHER");
            softly.assertThat(row.arrivals().getFirst().etaReason()).isEqualTo(SalmonRouteRows.VEHICLE_NOT_MATCHED);
        });
    }

    @Test
    void GBIS에만_있는_차량은_지평_밖이면_BEYOND_HORIZON_안이면_NOT_MATCHED다() {
        // given
        Board board = board(new ApproachingVehicle("V1", 2, available(0.8)));
        RouteArrivals gbis = gbis(prediction("FAR", 2, 900, 15), prediction("NEAR", 1, 120, 5));

        // when
        StationDetailOverview.RouteView row = SalmonRouteRows.of(VISIT, board, null, gbis, true, null, NOON);

        // then
        assertThat(row.arrivals()).allSatisfy(vehicle -> assertThat(vehicle.forecast()).isNotNull());
        assertSoftly(softly -> {
            softly.assertThat(row.arrivals()).extracting(StationDetailOverview.VehicleView::vehicleId)
                .containsExactly("V1", "NEAR", "FAR");
            softly.assertThat(row.arrivals()).extracting(StationDetailOverview.VehicleView::positionSource)
                .containsExactly(
                    StationDetailOverview.PositionSource.BOARD,
                    StationDetailOverview.PositionSource.ARRIVAL,
                    StationDetailOverview.PositionSource.ARRIVAL
                );
            softly.assertThat(row.arrivals()).extracting(vehicle -> vehicle.forecast().status())
                .containsExactly("AVAILABLE", "NOT_MATCHED", "BEYOND_HORIZON");
        });
    }

    @Test
    void 판정보드를_못_읽으면_GBIS_차량에_실패_사유를_달고_시간표는_UNKNOWN이다() {
        // given
        RouteArrivals gbis = gbis(prediction("V1", 1, 40, 1));

        // when
        StationDetailOverview.RouteView row = SalmonRouteRows.of(
            VISIT, null, "BOARD_UNAVAILABLE", gbis, true, null, NOON
        );

        // then
        assertThat(row.arrivals()).singleElement().satisfies(vehicle -> {
            assertThat(vehicle.forecast()).isNotNull();
            assertSoftly(softly -> {
                softly.assertThat(vehicle.positionSource()).isEqualTo(StationDetailOverview.PositionSource.ARRIVAL);
                softly.assertThat(vehicle.forecast().status()).isEqualTo("BOARD_UNAVAILABLE");
            });
        });
        assertThat(row.service()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(row.service().schedule()).isNull();
            softly.assertThat(row.service().scheduleWindow()).isEqualTo(ScheduleWindow.UNKNOWN);
        });
    }

    @Test
    void 방문과_판정보드의_노선_판본이_다르면_판정보드를_결합하지_않는다() {
        // given
        ArrivalVisit otherVersion = new ArrivalVisit(
            ROUTE_ID, ROUTE_ID, 5, STOP_SEQUENCE, "UP", NOW.minusSeconds(3600)
        );
        Board board = board(new ApproachingVehicle("V1", 1, available(0.9)));
        RouteArrivals gbis = gbis(prediction("V1", 1, 40, 2));

        // when
        StationDetailOverview.RouteView row = SalmonRouteRows.of(otherVersion, board, null, gbis, true, null, NOON);

        // then
        assertThat(row.arrivals()).singleElement().satisfies(vehicle -> {
            assertThat(vehicle.forecast()).isNotNull();
            assertSoftly(softly -> {
                softly.assertThat(vehicle.positionSource()).isEqualTo(StationDetailOverview.PositionSource.ARRIVAL);
                softly.assertThat(vehicle.forecast().status()).isEqualTo("BOARD_UNAVAILABLE");
            });
        });
        assertThat(row.service()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(row.service().referenceVersionId()).isEqualTo("5");
            softly.assertThat(row.service().routeStatus()).isEqualTo("PREPARING");
            softly.assertThat(row.service().schedule()).isNull();
            softly.assertThat(row.service().scheduleWindow()).isEqualTo(ScheduleWindow.UNKNOWN);
        });
    }

    @Test
    void 차량이_없으면_운행_종료라_하지_않고_시간표와_시간표_창만_낸다() {
        // when
        StationDetailOverview.RouteView row = SalmonRouteRows.of(
            VISIT, board(), null, gbis(), true, null, LocalTime.of(23, 0)
        );

        // then
        assertThat(row.service()).isNotNull();
        assertThat(row.service().schedule()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(row.arrivals()).isEmpty();
            softly.assertThat(row.status()).isEqualTo("NO_PREDICTION");
            softly.assertThat(row.reason()).isEqualTo(SalmonRouteRows.NO_APPROACHING_VEHICLE);
            softly.assertThat(row.service().schedule().firstDepartureTime()).isEqualTo("05:00");
            softly.assertThat(row.service().schedule().lastDepartureTime()).isEqualTo("22:30");
            softly.assertThat(row.service().scheduleWindow()).isEqualTo(ScheduleWindow.AFTER_LAST_DEPARTURE);
            softly.assertThat(row.destinationName()).isEqualTo("서울역");
        });
    }

    private static Board board(ApproachingVehicle... vehicles) {
        BoardRoute route = new BoardRoute(
            ROUTE_ID,
            "1150",
            "외국어대학교",
            "서울역",
            RouteStatus.FORECAST_READY,
            null,
            List.of(new DirectionInfo(BoardDirection.UP, "서울역 방면", "외국어대학교", "서울역", "05:00", "22:30")),
            "4",
            RouteHeadways.none()
        );
        OffsetDateTime observed = NOW.atOffset(ZoneOffset.ofHours(9));
        return new Board(
            route,
            observed,
            observed.plusMinutes(5),
            new ForecastModel(1, "release", observed.minusDays(1)),
            vehicles.length,
            List.of(new StopState(STOP_SEQUENCE, "206000532", "판교역", BoardDirection.UP, true, List.of(vehicles)))
        );
    }

    private static RouteArrivals gbis(ArrivalPrediction... predictions) {
        return new RouteArrivals(ROUTE_ID, "1150", VISIT, STOP_SEQUENCE, List.of(predictions));
    }

    private static ArrivalPrediction prediction(
        String vehicleId,
        final int ordinal,
        final int seconds,
        final int stops
    ) {
        ArrivalSnapshot.Prediction source = new ArrivalSnapshot.Prediction(
            vehicleId, ordinal, seconds, null, stops, 10, null, null, null, null
        );
        return ArrivalPrediction.at(source, NOW, NOW);
    }

    private static VehicleForecast available(final double probability) {
        return new VehicleForecast.Available(probability, 10.0);
    }
}
