package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.board.domain.DirectionInfo;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.board.domain.VehicleForecast.Available;
import com.gustler.backend.api.board.domain.VehicleForecast.Unavailable;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperations;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 같은 관측 판본의 노선·모델·차량·예보를 조합해 정류장별 탑승판을 만든다.
 * 읽는 동안 판본이 섞이지 않도록 반복 읽기 트랜잭션을 사용한다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
@RequiredArgsConstructor
public class BoardQueryService {
    private static final int MAX_APPROACHING_VEHICLES = 3;
    private static final Pattern DEPARTURE_TIME_FORMAT = Pattern.compile(
        "([01][0-9]|2[0-3]):[0-5][0-9]"
    );
    private static final int MAX_HORIZON_STOPS = 12;
    private static final Comparator<Approach> APPROACH_ORDER = Comparator
        .comparingInt(Approach::remainingStops)
        .thenComparingInt(Approach::horizonStops)
        .thenComparing(Approach::vehicleId, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparingInt(Approach::sourceRowNumber);

    private final BoardQueryRepository boardQueryRepository;
    private final BoardFreshnessPolicy freshnessPolicy;
    private final BoardCachePolicy cachePolicy;
    private RouteOperations operations;

    @Autowired(required = false)
    public void setOperations(RouteOperations value) {
        operations = value;
    }

    public BoardOverview getBoard(RouteId routeId) {
        BoardReading reading = readBoard(routeId);
        return new BoardOverview(reading.board(), reading.cacheMaxAge());
    }

    public BoardReading readBoard(RouteId routeId) {
        BoardSnapshot snapshot = boardQueryRepository.findSnapshot(routeId)
            .orElseThrow(RouteNotFoundException::new);
        ForecastModel activeModel = snapshot.activeModel()
            .orElseThrow(ModelOutOfScopeException::new);
        SnapshotObservation observation = snapshot.observation()
            .orElseThrow(NoRecentObservationException::new);

        if (freshnessPolicy.isStale(observation.observedAt())) {
            throw new NoRecentObservationException();
        }
        if (observation.vehiclesInService() == null
            || observation.vehiclesInService() < 0) {
            throw new ServiceUnavailableException();
        }

        List<BoardStop> stops = boardQueryRepository.findStops(
            snapshot.routeVersionId()
        );
        if (stops.isEmpty()) {
            throw new ServiceUnavailableException();
        }

        final boolean publicForecast =
            operations == null
                || operations
                    .find(routeId.value())
                    .map(RouteOperation::serviceEnabled)
                    .orElse(false);
        List<StoredPrediction> predictions = visiblePredictions(observation, publicForecast);
        ForecastModel model = modelOf(predictions, activeModel);
        List<BoardVehicleObservation> vehicles = boardQueryRepository.findObservedVehicles(
            observation.batchId(), snapshot.routeVersionId());
        Map<Integer, Map<Integer, StoredPrediction>> predictionsByStop = predictions.stream()
            .collect(Collectors.groupingBy(StoredPrediction::targetStopOrder,
                Collectors.toMap(StoredPrediction::sourceRowNumber, Function.identity())));
        List<StopState> stopStates = stops.stream()
            .map(stop -> toStopState(stop, vehicles, predictionsByStop.getOrDefault(stop.sequence(), Map.of())))
            .toList();

        RouteStatus status =
            RouteStatus.from(
                snapshot.activeModel().isPresent(),
                publicForecast && observation.forecastPublished());
        Board board = new Board(
            toBoardRoute(snapshot, stops, status),
            observation.observedAt(),
            freshnessPolicy.staleAt(observation.observedAt()),
            model,
            observation.vehiclesInService(),
            stopStates
        );
        return new BoardReading(
            board,
            observation.batchId(),
            cachePolicy.maxAgeAt(observation.observedAt())
        );
    }

    private List<StoredPrediction> visiblePredictions(SnapshotObservation observation, final boolean publicForecast) {
        if (publicForecast) {
            return publishedPredictions(observation);
        }
        return List.of();
    }

    private static double seatAvailableProbability(final double seatFullChance) {
        return 1.0d - seatFullChance;
    }

    private ForecastModel modelOf(List<StoredPrediction> predictions, ForecastModel activeModel) {
        if (predictions.isEmpty()) {
            return activeModel;
        }

        List<ForecastModel> models = predictions.stream()
            .map(StoredPrediction::model)
            .distinct()
            .toList();
        if (models.size() != 1) {
            throw new ServiceUnavailableException();
        }
        return models.getFirst();
    }

    private boolean hasValidForecastValues(StoredPrediction prediction) {
        final double seatFullChance = prediction.seatFullChance();
        return Double.isFinite(seatFullChance)
            && seatFullChance >= 0.0d
            && seatFullChance <= 1.0d;
    }

    private VehicleForecast forecastOf(StoredPrediction prediction) {
        if (prediction == null || !hasValidForecastValues(prediction)) {
            return new Unavailable();
        }
        return new Available(
            seatAvailableProbability(prediction.seatFullChance()),
            reportableExpectedSeats(prediction.expectedSeats()));
    }

    /**
     * 응답으로 제공할 기대 잔여석이다. 제공할 수 없으면 값을 비운다.
     *
     * <p>기대 잔여석의 유효성과 차량의 접근 여부는 별개다. 기대 잔여석이 유효하지 않다는 이유로
     * 차량을 제외하면 화면에서도 그 차량이 사라진다. API 문서는 이 값의 부재를 허용하므로
     * 기대 잔여석만 비우고 차량은 응답에 남긴다.
     *
     * <p>실제로 걸러지는 값은 무한대뿐이다. 음수와 NaN은 {@code ck_forecast_expected_seats}가 막는다.
     */
    private Double reportableExpectedSeats(Double expectedSeats) {
        if (expectedSeats == null || isUnreportableExpectedSeats(expectedSeats)) {
            return null;
        }
        return expectedSeats;
    }

    private boolean isUnreportableExpectedSeats(double expectedSeats) {
        return !Double.isFinite(expectedSeats) || expectedSeats < 0.0d;
    }

    private StopState toStopState(
        BoardStop stop,
        List<BoardVehicleObservation> vehicles,
        Map<Integer, StoredPrediction> predictionsBySourceRow
    ) {
        List<ApproachingVehicle> approachingVehicles = approachingVehicles(stop, vehicles, predictionsBySourceRow);
        return new StopState(
            stop.sequence(),
            stop.stopId(),
            stop.name(),
            stop.direction(),
            stop.boardingAllowed(),
            approachingVehicles
        );
    }

    private BoardRoute toBoardRoute(BoardSnapshot snapshot, List<BoardStop> stops, RouteStatus status) {
        Route route = snapshot.route();
        DepartureSchedule schedule = snapshot.schedule();
        return new BoardRoute(
            route.id(),
            route.displayName(),
            route.startStopName(),
            route.endStopName(),
            status,
            snapshot.turnSequence(),
            directionsOf(snapshot, stops),
            String.valueOf(snapshot.routeVersionId()),
            schedule.headways()
        );
    }

    private List<DirectionInfo> directionsOf(BoardSnapshot snapshot, List<BoardStop> stops) {
        if (snapshot.turnSequence() == null) {
            return List.of(singleDirectionOf(snapshot.schedule(), stops));
        }
        return roundTripDirectionsOf(snapshot, stops);
    }

    private DirectionInfo singleDirectionOf(DepartureSchedule schedule, List<BoardStop> stops) {
        Set<BoardDirection> directions = stops.stream()
            .map(BoardStop::direction)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(BoardDirection.class)));
        if (directions.size() != 1) {
            throw new ServiceUnavailableException();
        }

        BoardDirection direction = directions.iterator().next();
        BoardStop origin = stops.getFirst();
        BoardStop terminal = stops.getLast();
        String firstDepartureTime = firstDepartureTime(schedule, direction);
        String lastDepartureTime = lastDepartureTime(schedule, direction);

        return new DirectionInfo(
            direction,
            directionName(terminal),
            origin.name(),
            terminal.name(),
            firstDepartureTime,
            lastDepartureTime
        );
    }

    private List<DirectionInfo> roundTripDirectionsOf(BoardSnapshot snapshot, List<BoardStop> stops) {
        final int turnSequence = snapshot.turnSequence();
        Map<Integer, BoardStop> stopsBySequence = stops.stream()
            .collect(Collectors.toMap(
                BoardStop::sequence,
                Function.identity(),
                (first, second) -> first,
                LinkedHashMap::new
            ));
        BoardStop origin = stops.getFirst();
        BoardStop turn = stopsBySequence.get(turnSequence);
        BoardStop terminal = stops.getLast();
        if (isInvalidTurningStop(turn, origin, terminal)) {
            throw new ServiceUnavailableException();
        }

        requireRoundTripDirections(stops, turnSequence);

        DepartureSchedule schedule = snapshot.schedule();
        List<DirectionInfo> directions = new ArrayList<>(2);
        directions.add(new DirectionInfo(
            BoardDirection.UP,
            directionName(turn),
            origin.name(),
            turn.name(),
            requireDepartureTime(schedule.upFirstDepartureTime()),
            requireDepartureTime(schedule.upLastDepartureTime())
        ));
        directions.add(new DirectionInfo(
            BoardDirection.DOWN,
            directionName(terminal),
            turn.name(),
            terminal.name(),
            requireDepartureTime(schedule.downFirstDepartureTime()),
            requireDepartureTime(schedule.downLastDepartureTime())
        ));
        return List.copyOf(directions);
    }

    private boolean isInvalidTurningStop(BoardStop turn, BoardStop origin, BoardStop terminal) {
        if (turn == null || turn == origin) {
            return true;
        }
        return turn == terminal;
    }

    private void requireRoundTripDirections(List<BoardStop> stops, final int turnSequence) {
        final boolean inconsistent = stops.stream()
            .anyMatch(stop -> hasUnexpectedDirection(stop, turnSequence));
        if (inconsistent) {
            throw new ServiceUnavailableException();
        }
    }

    private boolean hasUnexpectedDirection(BoardStop stop, int turnSequence) {
        if (stop.sequence() <= turnSequence) {
            return stop.direction() != BoardDirection.UP;
        }
        return stop.direction() != BoardDirection.DOWN;
    }

    private String directionName(BoardStop terminal) {
        return terminal.name() + " 방면";
    }

    private String requireDepartureTime(String value) {
        if (value == null || !DEPARTURE_TIME_FORMAT.matcher(value).matches()) {
            throw new ServiceUnavailableException();
        }
        return value;
    }

    private List<StoredPrediction> publishedPredictions(SnapshotObservation observation) {
        if (observation.forecastPublished()) {
            return boardQueryRepository.findPredictions(observation.batchId());
        }
        return List.of();
    }

    private List<ApproachingVehicle> approachingVehicles(
        BoardStop stop,
        List<BoardVehicleObservation> vehicles,
        Map<Integer, StoredPrediction> predictionsBySourceRow
    ) {
        if (!stop.boardingAllowed()) {
            return List.of();
        }
        return vehicles.stream()
            .map(vehicle -> Approach.of(stop, vehicle))
            .filter(Approach::isDisplayable)
            .sorted(APPROACH_ORDER)
            .limit(MAX_APPROACHING_VEHICLES)
            .map(approach -> new ApproachingVehicle(
                approach.vehicleId(), approach.remainingStops(),
                forecastOf(predictionsBySourceRow.get(approach.sourceRowNumber()))))
            .toList();
    }

    private String firstDepartureTime(DepartureSchedule schedule, BoardDirection direction) {
        if (direction == BoardDirection.UP) {
            return requireDepartureTime(schedule.upFirstDepartureTime());
        }
        return requireDepartureTime(schedule.downFirstDepartureTime());
    }

    private String lastDepartureTime(DepartureSchedule schedule, BoardDirection direction) {
        if (direction == BoardDirection.UP) {
            return requireDepartureTime(schedule.upLastDepartureTime());
        }
        return requireDepartureTime(schedule.downLastDepartureTime());
    }

    /**
     * 한 정류장에 대한 차량의 모델 거리와 화면 거리다.
     *
     * <p>모델 거리는 대상 순번에서 버스가 지난 정류장 순번을 뺀 값이고, 노출 범위와 예보 거리가 이 값을 따른다.
     * 화면 거리는 대상 순번에서 버스가 있는 정류장 순번(GBIS stationSeq)을 뺀 값이고, 0이면 대상 정류장에 도착 중이다.
     * 노출 범위는 모델 거리 1~12로 정한다. 화면 거리는 모델 거리와 같거나
     * 정류장에 서 있는 차량이면 하나 작아야 한다. 두 순번의 정합을 DB가 보장하지 않으므로 이 관계가
     * 깨진 관측은 노출하지 않는다. 화면 거리는 0 이상이다.
     */
    private record Approach(BoardVehicleObservation vehicle, int horizonStops, int remainingStops) {
        static Approach of(BoardStop stop, BoardVehicleObservation vehicle) {
            return new Approach(
                vehicle,
                stop.sequence() - vehicle.passedStopOrder(),
                stop.sequence() - vehicle.stopOrder()
            );
        }

        String vehicleId() {
            return vehicle.vehicleId();
        }

        int sourceRowNumber() {
            return vehicle.sourceRowNumber();
        }

        boolean isDisplayable() {
            return isWithinHorizon() && isConsistent();
        }

        private boolean isWithinHorizon() {
            return horizonStops >= 1 && horizonStops <= MAX_HORIZON_STOPS;
        }

        private boolean isConsistent() {
            return remainingStops == horizonStops || remainingStops == horizonStops - 1;
        }
    }
}
