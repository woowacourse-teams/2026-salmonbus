package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.boarding.BoardingOptionsException;
import com.gustler.backend.api.boarding.application.BoardingOptionsQuery;
import com.gustler.backend.api.boarding.application.BoardingOptionsQueryService;
import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.BoardingOptions;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Option;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.boarding.domain.StopVisit;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Situation;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 공개 조회가 전광판, 도착 예측, 같은 방향 도보를 운영 값으로 판정 상황에 담는다.
 *
 * <p>대기 위험은 마감까지 그 정류장 방문에 닿는 도착 목록의 차량마다 전광판이 출발 만차 확률을 줄 때만 만든다.
 * 한 대라도 확률이 없으면 만들지 않는다. 마감 뒤에 오는 차량의 확률은 대기 위험이 아니다. 도착을 이어 붙인 가려진
 * 차량의 확률은 따로 넘긴다. 전광판은 한 번만 읽는다.
 * 이동 후보의 차내 시간과 접속 시각은 만들지 않는다. 전광판 좌석 예보는 만료 시각과 같은 순간까지 쓴다.
 */
@Component
public class BoardSalmonActionCase implements SalmonActionCase {
    private static final String DEPARTURE_ZERO = "DEPARTURE_ZERO";

    private final BoardQueryService boards;
    private final SalmonDueArrivals arrivals;
    private final BoardingOptionsQueryService walks;
    private final SalmonActionAssessment assessment;
    private final SalmonActionSettings settings;
    private final Clock clock;

    public BoardSalmonActionCase(
        BoardQueryService boards,
        SalmonDueArrivals arrivals,
        BoardingOptionsQueryService walks,
        SalmonActionAssessment assessment,
        SalmonActionSettings settings,
        Clock clock
    ) {
        this.boards = boards;
        this.arrivals = arrivals;
        this.walks = walks;
        this.assessment = assessment;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public Situation situation(RouteId routeId, String stationId, int stopSequence) {
        try {
            Instant decisionAt = clock.instant();
            Board board = boards.readBoard(routeId).board();
            if (decisionAt.isAfter(board.staleAt().toInstant())) {
                return Situation.missingStay();
            }
            SalmonDueArrivals.Due due = arrivals.vehicles(routeId.value(), board, stationId, stopSequence, decisionAt);
            List<Double> listed = new ArrayList<>();
            for (String vehicleId : due.listed()) {
                Optional<Double> probability = departureFullProbability(board, stationId, stopSequence, vehicleId);
                if (probability.isEmpty()) {
                    return Situation.missingStay();
                }
                listed.add(probability.get());
            }
            if (listed.isEmpty()) {
                return Situation.missingStay();
            }
            List<Optional<Double>> hidden = due.hidden().stream()
                .map(vehicleId -> departureFullProbability(board, stationId, stopSequence, vehicleId))
                .toList();
            BoardingOptionsQuery query = walkQuery(routeId, stationId, stopSequence);
            BoardingOptions options = walks.get(query);
            return assessment.situation(listed, hidden, toWalks(options), options.options().size() < query.limit());
        } catch (NoRecentObservationException | ModelOutOfScopeException | ServiceUnavailableException
                 | BoardingOptionsException | DataAccessException unavailable) {
            return Situation.missingStay();
        }
    }

    private BoardingOptionsQuery walkQuery(RouteId routeId, String stationId, int stopSequence) {
        return new BoardingOptionsQuery(routeId, stationId, stopSequence, null, Set.of(AccessMode.WALK),
            settings.maxWalkMinutes(), BoardingOptionsQuery.MAX_LIMIT,
            EnumSet.of(StopRelation.BEFORE, StopRelation.AFTER), true);
    }

    private static List<SalmonActionWalk> toWalks(BoardingOptions options) {
        List<SalmonActionWalk> walks = new ArrayList<>();
        for (Option option : options.options()) {
            StopVisit stop = option.stop();
            walks.add(new SalmonActionWalk(stop.stationId() + ":" + stop.stopSequence(),
                option.fastestSeconds() * 1000L, 0, Optional.empty(), Optional.empty()));
        }
        return walks;
    }

    private static Optional<Double> departureFullProbability(Board board, String stationId, int stopSequence,
                                                             String vehicleId) {
        List<StopState> stops = board.stops().stream()
            .filter(stop -> stop.sequence() == stopSequence && stationId.equals(stop.stopId()))
            .toList();
        if (stops.size() != 1) {
            return Optional.empty();
        }
        List<ApproachingVehicle> vehicles = stops.getFirst().approachingVehicles().stream()
            .filter(vehicle -> vehicleId.equals(vehicle.vehicleId()))
            .toList();
        if (vehicles.size() != 1 || !(vehicles.getFirst().forecast() instanceof VehicleForecast.Available available)
            || !DEPARTURE_ZERO.equals(available.probabilityTarget())) {
            return Optional.empty();
        }
        return Optional.ofNullable(available.departureFullProbability());
    }
}
