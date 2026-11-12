package com.gustler.backend.api.push.delivery;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.route.RouteId;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class NotificationMessageResolver implements Function<FavoriteTarget, NotificationMessage> {
    private final BoardQueryService boards;
    private final ArrivalQueryService arrivals;
    private final Clock clock;

    public NotificationMessageResolver(BoardQueryService boards, ArrivalQueryService arrivals, Clock clock) {
        this.boards = boards;
        this.arrivals = arrivals;
        this.clock = clock;
    }

    @Override
    public NotificationMessage apply(FavoriteTarget target) {
        try {
            Board board = boards.getBoard(new RouteId(target.routeId())).board();
            var stops = board.stops().stream()
                .filter(StopState::boardingAllowed)
                .filter(stop -> stop.stopId().equals(target.stopId()))
                .filter(stop -> stop.direction().name().equals(target.direction().name()))
                .toList();
            if (stops.size() != 1) return NotificationMessage.unavailable();
            StopState stop = stops.getFirst();
            String routeName = board.route().displayName();
            String title = (routeName.endsWith("번") ? routeName : routeName + "번") + " · " + stop.name();
            if (!board.staleAt().toInstant().isAfter(clock.instant())) {
                return new NotificationMessage(title, "버스 정보를 확인할 수 없어요. 앱에서 다시 확인해 주세요.");
            }
            if (stop.approachingVehicles().isEmpty()) {
                return new NotificationMessage(title, "접근 중인 버스가 없어요. 앱에서 운행 정보를 확인해 주세요.");
            }
            var vehicle = stop.approachingVehicles().getFirst();
            String eta = arrivalText(board, stop, vehicle.vehicleId());
            // 도착정보를 기다리는 사이 좌석 예보가 만료되면 숫자를 보내지 않는다.
            VehicleForecast forecast = board.staleAt().toInstant().isAfter(clock.instant())
                ? vehicle.forecast() : new VehicleForecast.Unavailable();
            return new NotificationMessage(title, eta + " · " + probabilityText(forecast)
                + "\n" + seatsText(forecast));
        } catch (RuntimeException exception) {
            return NotificationMessage.unavailable();
        }
    }

    private String arrivalText(Board board, StopState stop, String vehicleId) {
        if (vehicleId == null) return "도착 정보 없음";
        try {
            var overview = arrivals.get(new ArrivalQuery(stop.stopId(), board.route().id(),
                Long.parseLong(board.route().referenceVersionId()), stop.sequence()));
            // 같은 노선이라도 차량과 정류장 방문이 다르면 좌석 예보에 연결하지 않는다.
            ArrivalPrediction prediction = overview.routes().stream()
                .filter(route -> route.routeId().equals(board.route().id()))
                .filter(route -> route.targetVisit() != null && route.targetVisit().stopSequence() == stop.sequence())
                .flatMap(route -> route.arrivals().stream())
                .filter(candidate -> vehicleId.equals(candidate.source().vehicleId()))
                .filter(ArrivalPrediction::available)
                .findFirst().orElse(null);
            if (prediction == null) return "도착 정보 없음";
            long seconds = Duration.between(clock.instant(), prediction.expectedArrivalAt()).getSeconds();
            if (seconds < 0) return "도착 정보 없음";
            if (seconds < 60) return "곧 도착";
            return "약 " + ((seconds + 59) / 60) + "분 후 도착";
        } catch (RuntimeException exception) {
            return "도착 정보 없음";
        }
    }

    private static String probabilityText(VehicleForecast forecast) {
        if (!(forecast instanceof VehicleForecast.Available available)) return "탑승확률 정보 없음";
        double probability = available.seatAvailableProbability();
        if (!Double.isFinite(probability) || probability < 0 || probability > 1) return "탑승확률 정보 없음";
        return "탑승확률 " + (probability >= 0.7 ? "높음" : probability >= 0.3 ? "낮음" : "매우 낮음");
    }

    private static String seatsText(VehicleForecast forecast) {
        if (forecast instanceof VehicleForecast.Available available && available.expectedSeats() != null) {
            double seats = available.expectedSeats();
            if (Double.isFinite(seats) && seats >= 0) return "도착 시 빈 좌석 " + (long) Math.floor(seats) + "석 예상";
        }
        return "예상 빈 좌석 정보 없음";
    }
}
