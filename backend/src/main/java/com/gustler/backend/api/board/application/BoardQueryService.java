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
    private static final Pattern DEPARTURE_TIME = Pattern.compile(
        "([01][0-9]|2[0-3]):[0-5][0-9]"
    );
    private static final Comparator<StoredPrediction> PREDICTION_ORDER = Comparator
        .comparingInt(StoredPrediction::horizonStops)
        .thenComparing(
            StoredPrediction::vehicleId,
            Comparator.nullsLast(Comparator.naturalOrder())
        )
        .thenComparingInt(StoredPrediction::sourceRowNumber);

    private final BoardQueryRepository boardQueryRepository;
    private final BoardFreshnessPolicy freshnessPolicy;
    private final BoardCachePolicy cachePolicy;

    public BoardOverview getBoard(RouteId routeId) {
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
        ForecastModel model = predictions.isEmpty()
            ? activeModel
            : predictions.getFirst().model();
        Map<Integer, List<ApproachingVehicle>> predictionsByStop = groupPredictions(
            predictions
        );
        List<StopState> stopStates = stops.stream()
            .map(stop -> toStopState(stop, predictionsByStop))
            .toList();

        Board board = new Board(
            toBoardRoute(snapshot, stops),
            observation.observedAt(),
            model,
            observation.vehiclesInService(),
            stopStates
        );
        return new BoardOverview(
            board,
            cachePolicy.maxAgeAt(observation.observedAt())
        );
    }

    static double seatAvailableProbability(double pFull) {
        return 1.0d - pFull;
    }

    private Map<Integer, List<ApproachingVehicle>> groupPredictions(
        List<StoredPrediction> predictions
    ) {
        return predictions.stream()
            .filter(this::hasValidNumbers)
            .sorted(Comparator
                .comparingInt(StoredPrediction::targetStopOrder)
                .thenComparing(PREDICTION_ORDER))
            .collect(Collectors.groupingBy(
                StoredPrediction::targetStopOrder,
                LinkedHashMap::new,
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    rows -> rows.stream()
                        .limit(MAX_APPROACHING_VEHICLES)
                        .map(this::toApproachingVehicle)
                        .toList()
                )
            ));
    }

    private boolean hasValidNumbers(StoredPrediction prediction) {
        double pFull = prediction.pFull();
        if (!Double.isFinite(pFull) || pFull < 0.0d || pFull > 1.0d) {
            return false;
        }
        Double expectedSeats = prediction.expectedSeats();
        return expectedSeats == null
            || Double.isFinite(expectedSeats) && expectedSeats >= 0.0d;
    }

    private ApproachingVehicle toApproachingVehicle(StoredPrediction prediction) {
        return new ApproachingVehicle(
            prediction.vehicleId(),
            prediction.horizonStops(),
            seatAvailableProbability(prediction.pFull()),
            prediction.expectedSeats()
        );
    }

    private StopState toStopState(
        BoardStop stop,
        Map<Integer, List<ApproachingVehicle>> predictionsByStop
    ) {
        List<ApproachingVehicle> approachingVehicles = stop.boardingAllowed()
            ? predictionsByStop.getOrDefault(stop.sequence(), List.of())
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
        int turnSequence = snapshot.turnSequence();
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
        assertRoundTripDirections(stops, turnSequence);

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

    private void assertRoundTripDirections(
        List<BoardStop> stops,
        int turnSequence
    ) {
        boolean inconsistent = stops.stream().anyMatch(stop ->
            stop.sequence() <= turnSequence && stop.direction() != BoardDirection.UP
                || stop.sequence() > turnSequence && stop.direction() != BoardDirection.DOWN
        );
        if (inconsistent) {
            throw new ServiceUnavailableException();
        }
    }

    private String directionName(BoardStop terminal) {
        return terminal.name() + " 방면";
    }

    private String requireDepartureTime(String value) {
        if (value == null || !DEPARTURE_TIME.matcher(value).matches()) {
            throw new ServiceUnavailableException();
        }
        return value;
    }
}
