package com.gustler.backend.api.station.dto;

import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.arrival.dto.StationArrivalsResponse;
import com.gustler.backend.api.board.domain.HeadwayRange;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.station.application.StationDetailOverview;
import com.gustler.backend.api.station.domain.StationInfo;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 정류장 상세 응답. 오는 버스를 모두 담고 우리 노선(연어버스 노선)은 supported=true와 좌석 예보로 구분한다.
 *
 * <p>우리 노선의 차량·남은 정류장·예보는 판정보드 값이고, ETA·현재 좌석은 GBIS 도착정보 값이다(positionSource로 구분).
 * 정류장 단위 status·snapshot·refresh는 GBIS 수신 상태이며, 우리 노선 행은 GBIS가 막혀도 판정보드 차량을 낸다.
 */
@Schema(name = "StationDetailResponse")
public record StationDetailResponse(
    String stationId,
    String name,
    StationTileResponse.Point point,
    String coordinateSystem,
    @Schema(nullable = true) String regionName,
    @Schema(description = "같은 이름 정류장을 구별하는 방면(다음 정차 정류장 이름). 우리 노선이 없는 정류장은 null", nullable = true) String direction,
    @Schema(description = "지역명만으로 도착정보 부재를 단정하지 않음. 현재 null", nullable = true) String notice,
    Instant generatedAt,
    @Schema(
        description = "GBIS 도착정보 수신 상태",
        allowableValues = {
            "READY",
            "NO_PREDICTION",
            "UNAVAILABLE"
        }
    ) String status,
    @Schema(nullable = true) String reason,
    @Schema(nullable = true) StationArrivalsResponse.Snapshot snapshot,
    StationArrivalsResponse.Refresh refresh,
    @Schema(description = "우리 노선 먼저, 그다음 첫째 차량 ETA 오름차순") List<Route> routes
) {

    public StationDetailResponse {
        routes = List.copyOf(routes);
    }

    public static StationDetailResponse from(StationDetailOverview overview) {
        StationArrivalsOverview arrivals = overview.arrivals();
        StationInfo station = overview.station();
        return new StationDetailResponse(
            station.stationId(),
            station.name(),
            new StationTileResponse.Point(station.x(), station.y()),
            "WGS84",
            station.regionName(),
            station.direction(),
            null,
            arrivals.generatedAt(),
            arrivals.status().name(),
            arrivals.reason(),
            StationArrivalsResponse.snapshotOf(arrivals.snapshot()),
            StationArrivalsResponse.refreshOf(arrivals.refresh()),
            overview.routes()
                .stream()
                .map(StationDetailResponse::routeOf)
                .toList()
        );
    }

    private static Route routeOf(StationDetailOverview.RouteView view) {
        List<Vehicle> vehicles = new ArrayList<>();
        for (StationDetailOverview.VehicleView vehicle : view.arrivals()) {
            vehicles.add(vehicleOf(vehicle, vehicles.size() + 1));
        }
        return new Route(
            view.routeId(),
            view.name(),
            view.destinationName(),
            view.routeTypeCode(),
            view.supported(),
            serviceOf(view.service()),
            view.status(),
            view.reason(),
            vehicles
        );
    }

    private static Service serviceOf(StationDetailOverview.SalmonService service) {
        if (service == null) {
            return null;
        }
        return new Service(
            service.routeStatus(),
            service.referenceVersionId(),
            stopSequenceOf(service),
            service.direction(),
            service.matchStatus(),
            scheduleOf(service.schedule()),
            service.scheduleWindow().name()
        );
    }

    private static Integer stopSequenceOf(StationDetailOverview.SalmonService service) {
        if (service.stopSequence() > 0) {
            return service.stopSequence();
        }
        return null;
    }

    private static Schedule scheduleOf(StationDetailOverview.ScheduleView schedule) {
        if (schedule == null) {
            return null;
        }
        RouteHeadways headways = schedule.headways();
        return new Schedule(
            schedule.firstDepartureTime(),
            schedule.lastDepartureTime(),
            new Headways(
                rangeOf(headways.weekday()),
                rangeOf(headways.saturday()),
                rangeOf(headways.sunday()),
                rangeOf(headways.holiday())
            )
        );
    }

    private static Headway rangeOf(HeadwayRange range) {
        if (range == null) {
            return null;
        }
        return new Headway(range.minMinutes(), range.maxMinutes());
    }

    private static Vehicle vehicleOf(StationDetailOverview.VehicleView view, final int ordinal) {
        ArrivalPrediction prediction = view.prediction();
        if (prediction == null) {
            return new Vehicle(
                view.vehicleId(),
                ordinal,
                view.remainingStops(),
                view.positionSource().name(),
                Eta.unavailable(view.etaReason()),
                new StationArrivalsResponse.Seat("UNKNOWN", null, view.etaReason()),
                null,
                forecastOf(view.forecast())
            );
        }
        ArrivalSnapshot.Prediction source = prediction.source();
        return new Vehicle(
            view.vehicleId(),
            ordinal,
            view.remainingStops(),
            view.positionSource().name(),
            Eta.of(StationArrivalsResponse.etaOf(prediction)),
            StationArrivalsResponse.seatOf(source),
            source.congestion(),
            forecastOf(view.forecast())
        );
    }

    private static Forecast forecastOf(StationDetailOverview.ForecastView view) {
        if (view == null) {
            return null;
        }
        return new Forecast(view.status(), view.seatAvailableProbability(), view.expectedSeats());
    }

    public record Route(
        String routeId,
        @Schema(nullable = true) String name,
        @Schema(nullable = true) String destinationName,
        @Schema(nullable = true) Integer routeTypeCode,
        @Schema(description = "우리 노선(연어버스 노선)이면 true") boolean supported,
        @Schema(description = "우리 노선의 방문·노선 상태·시간표. 다른 노선은 null", nullable = true) Service service,
        @Schema(
            description = "우리 노선은 차량이 있으면 READY, 없으면 NO_PREDICTION. 다른 노선은 GBIS 기준",
            allowableValues = { "READY", "NO_PREDICTION", "UNAVAILABLE" }
        ) String status,
        @Schema(description = "우리 노선 차량이 없으면 NO_APPROACHING_VEHICLE", nullable = true) String reason,
        @Schema(description = "가까운 순") List<Vehicle> arrivals
    ) {

        public Route {
            arrivals = List.copyOf(arrivals);
        }
    }

    public record Service(
        @Schema(allowableValues = { "FORECAST_READY", "PREPARING" }) String routeStatus,
        @Schema(nullable = true) String referenceVersionId,
        @Schema(nullable = true) Integer stopSequence,
        @Schema(nullable = true) String direction,
        @Schema(allowableValues = { "MATCHED", "UNVERIFIED" }) String matchStatus,
        @Schema(description = "이 방향의 기점 첫차·막차 출발 시각과 배차. 판정보드를 못 읽으면 null", nullable = true) Schedule schedule,
        @Schema(
            description = "지금이 기점 출발 시각의 어디쯤인지. 운행 종료 판정이 아니며 차량이 있으면 차량이 우선이다",
            allowableValues = { "BEFORE_FIRST_DEPARTURE", "WITHIN_DEPARTURES", "AFTER_LAST_DEPARTURE", "UNKNOWN" }
        ) String scheduleWindow
    ) {
    }

    public record Schedule(
        @Schema(description = "HH:mm", nullable = true) String firstDepartureTime,
        @Schema(description = "HH:mm. 첫차보다 이르면 자정을 넘긴 막차", nullable = true) String lastDepartureTime,
        Headways headways
    ) {
    }

    public record Headways(
        @Schema(nullable = true) Headway weekday,
        @Schema(nullable = true) Headway saturday,
        @Schema(nullable = true) Headway sunday,
        @Schema(description = "일요일 값으로 대신하지 않는다", nullable = true) Headway holiday
    ) {
    }

    public record Headway(int minMinutes, int maxMinutes) {
    }

    public record Vehicle(
        @Schema(nullable = true) String vehicleId,
        @Schema(description = "이 행 안에서 가까운 순 1부터") int ordinal,
        @Schema(nullable = true) Integer remainingStops,
        @Schema(
            description = "remainingStops의 출처. BOARD는 판정보드(우리 관측), ARRIVAL은 GBIS 도착정보",
            allowableValues = { "BOARD", "ARRIVAL" }
        ) String positionSource,
        Eta eta,
        StationArrivalsResponse.Seat currentSeat,
        @Schema(nullable = true) Integer congestionCode,
        @Schema(description = "우리 노선 차량의 좌석 예보. 다른 노선은 null", nullable = true) Forecast forecast
    ) {
    }

    /**
     * GBIS 도착 예정. 판정보드에만 있는 차량은 status UNAVAILABLE이고 시각 필드가 null이다.
     */
    public record Eta(
        @Schema(allowableValues = { "AVAILABLE", "UNAVAILABLE" }) String status,
        @Schema(
            description = "PREDICTION_ELAPSED, ARRIVAL_UNAVAILABLE(GBIS 수신본 없음), VEHICLE_NOT_MATCHED(GBIS에 이 차량 없음)",
            nullable = true
        ) String reason,
        @Schema(allowableValues = { "SECONDS", "MINUTES" }, nullable = true) String resolution,
        @Schema(nullable = true) Instant estimateBaseAt,
        @Schema(allowableValues = "RECEIVED_AT_APPROXIMATION", nullable = true) String baseKind,
        @Schema(nullable = true) Instant expectedArrivalAt,
        @Schema(nullable = true, minimum = "0") Long remainingSeconds
    ) {

        static Eta of(StationArrivalsResponse.Eta eta) {
            return new Eta(
                eta.status(),
                eta.reason(),
                eta.resolution(),
                eta.estimateBaseAt(),
                eta.baseKind(),
                eta.expectedArrivalAt(),
                eta.remainingSeconds()
            );
        }

        static Eta unavailable(String reason) {
            return new Eta("UNAVAILABLE", reason, null, null, null, null, null);
        }
    }

    public record Forecast(
        @Schema(
            description = "BEYOND_HORIZON은 판정보드 예보 범위(12정류장)보다 먼 차량, NOT_MATCHED는 범위 안인데 판정보드에 없는 차량",
            allowableValues = {
                "AVAILABLE",
                "UNAVAILABLE",
                "NOT_MATCHED",
                "BEYOND_HORIZON",
                "PREPARING",
                "BOARD_UNAVAILABLE"
            }
        ) String status,
        @Schema(nullable = true) Double seatAvailableProbability,
        @Schema(nullable = true) Double expectedSeats
    ) {
    }
}
