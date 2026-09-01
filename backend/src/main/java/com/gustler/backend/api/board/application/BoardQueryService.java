package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.board.domain.DirectionInfo;
import com.gustler.backend.api.board.domain.ForecastHorizon;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.http.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
@RequiredArgsConstructor
public class BoardQueryService {

    private static final int MAX_APPROACHING_VEHICLES = 3;
    private static final Pattern DEPARTURE_TIME_FORMAT = Pattern.compile(
        "([01][0-9]|2[0-3]):[0-5][0-9]"
    );
    private static final Comparator<Candidate> APPROACHING_ORDER = Comparator
        .comparingInt((Candidate candidate) -> candidate.vehicle().horizonStops())
        .thenComparing(
            candidate -> candidate.vehicle().vehicleId(),
            Comparator.nullsLast(Comparator.naturalOrder())
        )
        .thenComparingInt(Candidate::sourceRowNumber);

    private final BoardQueryRepository boardQueryRepository;
    private final BoardFreshnessPolicy freshnessPolicy;
    private final BoardCachePolicy cachePolicy;

    public BoardOverview getBoard(
        RouteId routeId
    ) {
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

        List<StoredPrediction> predictions = boardQueryRepository.findPredictions(
            observation.batchId()
        );
        List<StoredObservation> observations = boardQueryRepository.findObservations(
            observation.batchId()
        );
        ForecastModel model = modelOf(predictions, activeModel);
        Map<Integer, List<ApproachingVehicle>> vehiclesByStop = groupVehicles(
            stops,
            observations,
            predictions
        );
        List<StopState> stopStates = stops.stream()
            .map(stop -> toStopState(stop, vehiclesByStop))
            .toList();

        Board board = new Board(
            toBoardRoute(snapshot, stops),
            observation.observedAt(),
            freshnessPolicy.staleAt(observation.observedAt()),
            model,
            observation.vehiclesInService(),
            stopStates
        );
        return new BoardOverview(
            board,
            cachePolicy.maxAgeAt(observation.observedAt())
        );
    }

    private static double seatAvailableProbability(
        final double seatFullChance
    ) {
        return 1.0d - seatFullChance;
    }

    private ForecastModel modelOf(
        List<StoredPrediction> predictions,
        ForecastModel activeModel
    ) {
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

    /**
     * 정류장마다 그리로 오고 있는 차량을 싣는다.
     *
     * <p>예보 표가 아니라 관측에서 출발한다. 예보 표에서 출발하면 예보 행이 없는 차량이 조인에서
     * 빠져 보드에서 조용히 사라진다. 앞 1~12정류장 중 승차 가능한 정류장에 차량을 싣고, 그 자리에
     * 쓸 수 있는 예보가 있으면 좌석 확률을 붙이고 없으면 좌석을 모른다고 낸다.
     */
    private Map<Integer, List<ApproachingVehicle>> groupVehicles(
        List<BoardStop> stops,
        List<StoredObservation> observations,
        List<StoredPrediction> predictions
    ) {
        Map<PredictionKey, StoredPrediction> usablePredictions = predictions.stream()
            .filter(this::hasValidForecastValues)
            .collect(Collectors.toMap(
                prediction -> new PredictionKey(
                    prediction.sourceRowNumber(),
                    prediction.targetStopOrder()
                ),
                Function.identity(),
                (first, second) -> first,
                LinkedHashMap::new
            ));

        Map<Integer, List<ApproachingVehicle>> vehiclesByStop = new LinkedHashMap<>();
        for (BoardStop stop : stops) {
            if (stop.boardingAllowed()) {
                vehiclesByStop.put(
                    stop.sequence(),
                    approachingVehiclesAt(stop, observations, usablePredictions)
                );
            }
        }
        return vehiclesByStop;
    }

    private List<ApproachingVehicle> approachingVehiclesAt(
        BoardStop stop,
        List<StoredObservation> observations,
        Map<PredictionKey, StoredPrediction> usablePredictions
    ) {
        List<Candidate> candidates = new ArrayList<>();
        for (StoredObservation observation : observations) {
            candidateAt(stop, observation, usablePredictions).ifPresent(candidates::add);
        }
        return candidates.stream()
            .sorted(APPROACHING_ORDER)
            .limit(MAX_APPROACHING_VEHICLES)
            .map(Candidate::vehicle)
            .toList();
    }

    /** 그 관측을 그 정류장에 실을지, 싣는다면 좌석을 아는 채로 실을지 정한다. */
    private Optional<Candidate> candidateAt(
        BoardStop stop,
        StoredObservation observation,
        Map<PredictionKey, StoredPrediction> usablePredictions
    ) {
        StoredPrediction prediction = usablePredictions.get(new PredictionKey(
            observation.sourceRowNumber(),
            stop.sequence()
        ));
        if (prediction != null) {
            return Optional.of(new Candidate(
                observation.sourceRowNumber(),
                new ApproachingVehicle.Forecast(
                    prediction.vehicleId(),
                    prediction.stopsToTarget(),
                    seatAvailableProbability(prediction.seatFullChance()),
                    prediction.expectedSeats()
                )
            ));
        }

        final int horizonStops = stop.sequence() - observation.passedStopOrder();
        if (!ForecastHorizon.covers(horizonStops)) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(
            observation.sourceRowNumber(),
            new ApproachingVehicle.SeatUnknown(
                observation.vehicleId(),
                horizonStops
            )
        ));
    }

    private boolean hasValidForecastValues(
        StoredPrediction prediction
    ) {
        final double seatFullChance = prediction.seatFullChance();
        if (!Double.isFinite(seatFullChance)
            || seatFullChance < 0.0d
            || seatFullChance > 1.0d) {
            return false;
        }
        Double expectedSeats = prediction.expectedSeats();
        return expectedSeats == null
            || Double.isFinite(expectedSeats) && expectedSeats >= 0.0d;
    }

    private StopState toStopState(
        BoardStop stop,
        Map<Integer, List<ApproachingVehicle>> vehiclesByStop
    ) {
        List<ApproachingVehicle> approachingVehicles = stop.boardingAllowed()
            ? vehiclesByStop.getOrDefault(stop.sequence(), List.of())
            : List.of();
        return new StopState(
            stop.sequence(),
            stop.stopId(),
            stop.name(),
            stop.direction(),
            stop.boardingAllowed(),
            approachingVehicles
        );
    }

    private BoardRoute toBoardRoute(
        BoardSnapshot snapshot,
        List<BoardStop> stops
    ) {
        Route route = snapshot.route();
        return new BoardRoute(
            route.id(),
            route.displayName(),
            route.startStopName(),
            route.endStopName(),
            RouteStatus.FORECAST_READY,
            snapshot.turnSequence(),
            directionsOf(snapshot, stops),
            String.valueOf(snapshot.routeVersionId())
        );
    }

    private List<DirectionInfo> directionsOf(
        BoardSnapshot snapshot,
        List<BoardStop> stops
    ) {
        if (snapshot.turnSequence() == null) {
            return List.of(singleDirectionOf(snapshot.schedule(), stops));
        }
        return roundTripDirectionsOf(snapshot, stops);
    }

    private DirectionInfo singleDirectionOf(
        DepartureSchedule schedule,
        List<BoardStop> stops
    ) {
        Set<BoardDirection> directions = stops.stream()
            .map(BoardStop::direction)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(BoardDirection.class)));
        if (directions.size() != 1) {
            throw new ServiceUnavailableException();
        }

        BoardDirection direction = directions.iterator().next();
        BoardStop origin = stops.getFirst();
        BoardStop terminal = stops.getLast();
        String firstDepartureTime = direction == BoardDirection.UP
            ? requireDepartureTime(schedule.upFirstDepartureTime())
            : requireDepartureTime(schedule.downFirstDepartureTime());
        String lastDepartureTime = direction == BoardDirection.UP
            ? requireDepartureTime(schedule.upLastDepartureTime())
            : requireDepartureTime(schedule.downLastDepartureTime());

        return new DirectionInfo(
            direction,
            directionName(terminal),
            origin.name(),
            terminal.name(),
            firstDepartureTime,
            lastDepartureTime
        );
    }

    private List<DirectionInfo> roundTripDirectionsOf(
        BoardSnapshot snapshot,
        List<BoardStop> stops
    ) {
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
        if (turn == null || turn == origin || turn == terminal) {
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

    private void requireRoundTripDirections(
        List<BoardStop> stops,
        final int turnSequence
    ) {
        final boolean inconsistent = stops.stream().anyMatch(stop ->
            stop.sequence() <= turnSequence && stop.direction() != BoardDirection.UP
                || stop.sequence() > turnSequence && stop.direction() != BoardDirection.DOWN
        );
        if (inconsistent) {
            throw new ServiceUnavailableException();
        }
    }

    private String directionName(
        BoardStop terminal
    ) {
        return terminal.name() + " 방면";
    }

    private String requireDepartureTime(
        String value
    ) {
        if (value == null || !DEPARTURE_TIME_FORMAT.matcher(value).matches()) {
            throw new ServiceUnavailableException();
        }
        return value;
    }

    /** 예보 한 줄을 가리키는 열쇠. 묶음 안에서 관측 행 번호와 대상 정류장 순번이 유일하다. */
    private record PredictionKey(
        int sourceRowNumber,
        int targetStopOrder
    ) {
    }

    /** 한 정류장에 실릴 후보. 정렬에서 마지막으로 가르는 관측 행 번호를 같이 든다. */
    private record Candidate(
        int sourceRowNumber,
        ApproachingVehicle vehicle
    ) {
    }
}
