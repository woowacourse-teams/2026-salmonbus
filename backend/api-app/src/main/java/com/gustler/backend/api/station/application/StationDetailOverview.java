package com.gustler.backend.api.station.application;

import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.station.domain.ScheduleWindow;
import com.gustler.backend.api.station.domain.StationInfo;
import java.time.Duration;
import java.util.List;

/**
 * 정류장 상세 조회 결과. 우리 노선(연어버스 노선)과 다른 노선을 한 목록에 담고 supported로 구분한다.
 * HTTP 응답 형식과 캐시 헤더로 바꾸는 일은 컨트롤러가 맡는다.
 */
public record StationDetailOverview(
    StationInfo station,
    StationArrivalsOverview arrivals,
    List<RouteView> routes,
    Duration cacheMaxAge
) {

    public StationDetailOverview {
        routes = List.copyOf(routes);
    }

    /**
     * 한 노선의 한 방문. supported가 참이면 service가 있다.
     */
    public record RouteView(
        String routeId,
        String name,
        String destinationName,
        Integer routeTypeCode,
        boolean supported,
        SalmonService service,
        String status,
        String reason,
        List<VehicleView> arrivals
    ) {

        public RouteView {
            arrivals = List.copyOf(arrivals);
        }
    }

    /**
     * 우리 노선 방문과 그 노선의 시간표. 판정보드를 못 읽으면 schedule은 null이고 scheduleWindow는 UNKNOWN이다.
     */
    public record SalmonService(
        String routeStatus,
        String referenceVersionId,
        int stopSequence,
        String direction,
        String matchStatus,
        ScheduleView schedule,
        ScheduleWindow scheduleWindow
    ) {
    }

    /**
     * 방향별 기점 첫차·막차 출발 시각과 요일별 배차. 이 정류장 통과 시각이 아니다.
     */
    public record ScheduleView(String firstDepartureTime, String lastDepartureTime, RouteHeadways headways) {
    }

    /**
     * 차량 하나.
     *
     * <p>우리 노선은 판정보드 차량(positionSource BOARD)이 바탕이고 prediction은 GBIS 같은 차량 값이다.
     * GBIS에 같은 차량이 없으면 prediction은 null이고 etaReason에 사유가 있다.
     * GBIS에만 있는 차량과 다른 노선 차량은 positionSource ARRIVAL이며 prediction이 항상 있다.
     */
    public record VehicleView(
        String vehicleId,
        Integer remainingStops,
        PositionSource positionSource,
        ArrivalPrediction prediction,
        String etaReason,
        ForecastView forecast
    ) {
    }

    public enum PositionSource {

        BOARD, ARRIVAL
    }

    public record ForecastView(String status, Double seatAvailableProbability, Double expectedSeats) {

        public static ForecastView of(String status) {
            return new ForecastView(status, null, null);
        }
    }
}
