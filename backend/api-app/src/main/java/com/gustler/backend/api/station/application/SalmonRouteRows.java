package com.gustler.backend.api.station.application;

import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.DirectionInfo;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.station.domain.ScheduleWindow;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 우리 노선 방문 하나를 상세 행으로 만든다.
 *
 * <p>차량·남은 정류장·좌석 예보는 판정보드가 기준이고 같은 차량 ID의 GBIS ETA·현재 좌석을 결합한다.
 * GBIS에만 있는 차량은 예보를 임의로 붙이지 않으며 판정보드 지평 밖이면 BEYOND_HORIZON으로 구분한다.
 */
final class SalmonRouteRows {

    static final String ARRIVAL_UNAVAILABLE = "ARRIVAL_UNAVAILABLE";

    static final String VEHICLE_NOT_MATCHED = "VEHICLE_NOT_MATCHED";

    static final String NO_APPROACHING_VEHICLE = "NO_APPROACHING_VEHICLE";

    private static final Comparator<StationDetailOverview.VehicleView> VEHICLE_ORDER = Comparator.comparing(
        StationDetailOverview.VehicleView::remainingStops,
        Comparator.nullsLast(Comparator.naturalOrder())
    )
        .thenComparing(
            SalmonRouteRows::remainingSecondsOf,
            Comparator.nullsLast(Comparator.naturalOrder())
        );

    private SalmonRouteRows() {
    }

    /**
     * @param board 읽기 실패 시 null이며 boardFailure에 사유가 있다. 방문과 노선 판본이 다르면 결합하지 않는다
     * @param gbis 해당 방문의 GBIS 도착정보. 수신본에 없으면 null
     * @param arrivalsReceived 수신본이 없으면 차량 ETA 사유를 ARRIVAL_UNAVAILABLE로 구분한다
     */
    static StationDetailOverview.RouteView of(
        ArrivalVisit visit,
        Board board,
        String boardFailure,
        RouteArrivals gbis,
        final boolean arrivalsReceived,
        ArrivalSnapshot.RouteArrival source,
        LocalTime now
    ) {
        if (board != null && !Long.toString(visit.referenceVersionId()).equals(board.route().referenceVersionId())) {
            board = null;
            boardFailure = "BOARD_UNAVAILABLE";
        }
        List<StationDetailOverview.VehicleView> vehicles =
            vehiclesOf(visit, board, boardFailure, gbis, arrivalsReceived);
        DirectionInfo direction = directionOf(board, visit.direction());
        StationDetailOverview.ScheduleView schedule = scheduleOf(board, direction);
        StationDetailOverview.SalmonService service = new StationDetailOverview.SalmonService(
            routeStatusOf(board),
            Long.toString(visit.referenceVersionId()),
            visit.stopSequence(),
            visit.direction(),
            "MATCHED",
            schedule,
            scheduleWindowOf(schedule, now)
        );
        return new StationDetailOverview.RouteView(
            visit.routeId(),
            nameOf(gbis, board),
            destinationOf(source, direction),
            routeTypeOf(source),
            true,
            service,
            vehicleStatusOf(vehicles),
            emptyReasonOf(vehicles),
            vehicles
        );
    }

    private static List<StationDetailOverview.VehicleView> vehiclesOf(
        ArrivalVisit visit,
        Board board,
        String boardFailure,
        RouteArrivals gbis,
        final boolean arrivalsReceived
    ) {
        if (board == null) {
            return gbisOnly(gbis, StationDetailOverview.ForecastView.of(boardFailure));
        }
        return merged(visit, board, gbis, arrivalsReceived);
    }

    private static StationDetailOverview.ScheduleView scheduleOf(Board board, DirectionInfo direction) {
        if (direction == null) {
            return null;
        }
        return new StationDetailOverview.ScheduleView(
            direction.firstDepartureTime(),
            direction.lastDepartureTime(),
            board.route().headways()
        );
    }

    private static ScheduleWindow scheduleWindowOf(StationDetailOverview.ScheduleView schedule, LocalTime now) {
        if (schedule == null) {
            return ScheduleWindow.UNKNOWN;
        }
        return ScheduleWindow.of(schedule.firstDepartureTime(), schedule.lastDepartureTime(), now);
    }

    private static String routeStatusOf(Board board) {
        if (board == null) {
            return "PREPARING";
        }
        return board.route().status().name();
    }

    private static String nameOf(RouteArrivals arrivals, Board board) {
        if (arrivals != null) {
            return arrivals.name();
        }
        if (board == null) {
            return null;
        }
        return board.route().displayName();
    }

    private static String destinationOf(ArrivalSnapshot.RouteArrival source, DirectionInfo direction) {
        if (source != null && source.destinationName() != null) {
            return source.destinationName();
        }
        return Optional.ofNullable(direction).map(DirectionInfo::terminalStopName).orElse(null);
    }

    private static Integer routeTypeOf(ArrivalSnapshot.RouteArrival source) {
        return Optional.ofNullable(source).map(ArrivalSnapshot.RouteArrival::routeType).orElse(null);
    }

    private static String vehicleStatusOf(List<StationDetailOverview.VehicleView> vehicles) {
        if (vehicles.isEmpty()) {
            return "NO_PREDICTION";
        }
        return "READY";
    }

    private static String emptyReasonOf(List<StationDetailOverview.VehicleView> vehicles) {
        if (vehicles.isEmpty()) {
            return NO_APPROACHING_VEHICLE;
        }
        return null;
    }

    /**
     * 우리 노선이지만 특정 방문과 일치하지 않은 GBIS 차량에는 예보를 붙이지 않는다.
     */
    static StationDetailOverview.RouteView unverified(RouteArrivals arrivals, ArrivalSnapshot.RouteArrival source) {
        List<StationDetailOverview.VehicleView> vehicles =
            gbisOnly(arrivals, StationDetailOverview.ForecastView.of("NOT_MATCHED"));
        StationDetailOverview.SalmonService service =
            new StationDetailOverview.SalmonService(
                "PREPARING", null, 0, null, "UNVERIFIED", null, ScheduleWindow.UNKNOWN
            );
        return new StationDetailOverview.RouteView(
            arrivals.routeId(),
            arrivals.name(),
            destinationOf(source, null),
            routeTypeOf(source),
            true,
            service,
            arrivals.status().name(),
            null,
            vehicles
        );
    }

    private static List<StationDetailOverview.VehicleView> merged(
        ArrivalVisit visit,
        Board board,
        RouteArrivals arrivals,
        final boolean arrivalsReceived
    ) {
        Map<String, ArrivalPrediction> predictionsByVehicle = new LinkedHashMap<>();
        List<ArrivalPrediction> predictionsWithoutId = new ArrayList<>();
        indexPredictions(arrivals, predictionsByVehicle, predictionsWithoutId);
        List<StationDetailOverview.VehicleView> vehicles =
            boardVehicles(visit, board, predictionsByVehicle, arrivalsReceived);
        List<ArrivalPrediction> unmatched = new ArrayList<>(predictionsByVehicle.values());
        unmatched.addAll(predictionsWithoutId);
        vehicles.addAll(unmatched.stream()
            .map(SalmonRouteRows::unmatchedVehicleOf)
            .toList());
        vehicles.sort(VEHICLE_ORDER);
        return vehicles;
    }

    private static void indexPredictions(
        RouteArrivals arrivals,
        Map<String, ArrivalPrediction> predictionsByVehicle,
        List<ArrivalPrediction> predictionsWithoutId
    ) {
        if (arrivals == null) {
            return;
        }
        for (ArrivalPrediction prediction : arrivals.arrivals()) {
            String vehicleId = prediction.source().vehicleId();
            if (vehicleId == null) {
                predictionsWithoutId.add(prediction);
                continue;
            }
            predictionsByVehicle.putIfAbsent(vehicleId, prediction);
        }
    }

    private static List<StationDetailOverview.VehicleView> boardVehicles(
        ArrivalVisit visit,
        Board board,
        Map<String, ArrivalPrediction> predictionsByVehicle,
        final boolean arrivalsReceived
    ) {
        List<StationDetailOverview.VehicleView> vehicles = new ArrayList<>();
        for (ApproachingVehicle vehicle : approachingOf(board, visit.stopSequence())) {
            ArrivalPrediction prediction = removePrediction(vehicle.vehicleId(), predictionsByVehicle);
            vehicles.add(
                new StationDetailOverview.VehicleView(
                    vehicle.vehicleId(),
                    vehicle.remainingStops(),
                    StationDetailOverview.PositionSource.BOARD,
                    prediction,
                    etaReasonOf(prediction, arrivalsReceived),
                    forecastOf(vehicle.forecast())
                )
            );
        }
        return vehicles;
    }

    private static ArrivalPrediction removePrediction(String vehicleId, Map<String, ArrivalPrediction> predictions) {
        if (vehicleId == null) {
            return null;
        }
        return predictions.remove(vehicleId);
    }

    private static String etaReasonOf(ArrivalPrediction prediction, final boolean arrivalsReceived) {
        if (prediction != null) {
            return null;
        }
        if (arrivalsReceived) {
            return VEHICLE_NOT_MATCHED;
        }
        return ARRIVAL_UNAVAILABLE;
    }

    private static StationDetailOverview.VehicleView unmatchedVehicleOf(ArrivalPrediction prediction) {
        Integer stops = prediction.source().remainingStops();
        return new StationDetailOverview.VehicleView(
            prediction.source().vehicleId(),
            stops,
            StationDetailOverview.PositionSource.ARRIVAL,
            prediction,
            null,
            StationDetailOverview.ForecastView.of(unmatchedStatusOf(stops))
        );
    }

    private static String unmatchedStatusOf(Integer remainingStops) {
        if (remainingStops != null && remainingStops > BoardQueryService.MAX_HORIZON_STOPS) {
            return "BEYOND_HORIZON";
        }
        return "NOT_MATCHED";
    }

    private static List<StationDetailOverview.VehicleView> gbisOnly(
        RouteArrivals arrivals,
        StationDetailOverview.ForecastView forecast
    ) {
        if (arrivals == null) {
            return List.of();
        }
        return arrivals.arrivals()
            .stream()
            .map(prediction -> new StationDetailOverview.VehicleView(
                prediction.source().vehicleId(),
                prediction.source().remainingStops(),
                StationDetailOverview.PositionSource.ARRIVAL,
                prediction,
                null,
                forecast
            ))
            .sorted(VEHICLE_ORDER)
            .toList();
    }

    private static List<ApproachingVehicle> approachingOf(Board board, final int stopSequence) {
        return board.stops()
            .stream()
            .filter(stop -> stop.sequence() == stopSequence)
            .map(StopState::approachingVehicles)
            .findFirst()
            .orElse(List.of());
    }

    private static DirectionInfo directionOf(Board board, String direction) {
        if (board == null || direction == null) {
            return null;
        }
        return board.route()
            .directions()
            .stream()
            .filter(candidate -> candidate.id().name().equals(direction))
            .findFirst()
            .orElse(null);
    }

    private static StationDetailOverview.ForecastView forecastOf(VehicleForecast forecast) {
        if (forecast instanceof VehicleForecast.Available available) {
            return new StationDetailOverview.ForecastView(
                "AVAILABLE",
                available.seatAvailableProbability(),
                available.expectedSeats()
            );
        }
        return StationDetailOverview.ForecastView.of("UNAVAILABLE");
    }

    static Long remainingSecondsOf(StationDetailOverview.VehicleView vehicle) {
        if (vehicle.prediction() == null) {
            return null;
        }
        return vehicle.prediction().remainingSeconds();
    }

    static boolean sameVisit(RouteArrivals route, ArrivalVisit visit) {
        if (route.targetVisit() == null) {
            return false;
        }
        return Objects.equals(route.routeId(), visit.routeId())
            && route.targetVisit().stopSequence() == visit.stopSequence();
    }
}
