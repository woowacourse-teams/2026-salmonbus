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
 * 공개 조회가 전광판과 같은 방향 도보를 운영 값으로 판정 상황에 담는다.
 *
 * <p>대기 위험은 그 정류장 방문의 출발 만차 확률이 하나일 때만 만든다. 둘 이상이면 독립으로 곱하지 않는다.
 * 차내 시간과 버스 도착 시각은 전광판에 없으므로 만들지 않는다. 전광판 좌석 예보는 만료 시각과 같은 순간까지 쓴다.
 */
@Component
public class BoardSalmonActionCase implements SalmonActionCase {
    private static final String DEPARTURE_ZERO = "DEPARTURE_ZERO";

    private final BoardQueryService boards;
    private final BoardingOptionsQueryService walks;
    private final SalmonActionAssessment assessment;
    private final SalmonEpochBudget epochBudget;
    private final SalmonWalkCap walkCap;
    private final Clock clock;

    public BoardSalmonActionCase(
        BoardQueryService boards,
        BoardingOptionsQueryService walks,
        SalmonActionAssessment assessment,
        SalmonEpochBudget epochBudget,
        SalmonWalkCap walkCap,
        Clock clock
    ) {
        this.boards = boards;
        this.walks = walks;
        this.assessment = assessment;
        this.epochBudget = epochBudget;
        this.walkCap = walkCap;
        this.clock = clock;
    }

    @Override
    public Situation situation(RouteId routeId, String stationId, int stopSequence) {
        try {
            Instant decisionAt = clock.instant();
            Optional<Board> settled = SalmonBaselineSettle.untilStable(epochBudget,
                () -> boards.readBoard(routeId).board(),
                board -> board.observedAt().toInstant());
            if (settled.isEmpty()) {
                return Situation.missingStay();
            }
            Board board = settled.get();
            if (decisionAt.isAfter(board.staleAt().toInstant())) {
                return Situation.missingStay();
            }
            List<Double> stay = departureFullProbabilities(board, stationId, stopSequence);
            if (stay.size() != 1) {
                return Situation.missingStay();
            }
            BoardingOptionsQuery query = walkQuery(routeId, stationId, stopSequence);
            BoardingOptions options = walks.get(query);
            return assessment.situation(stay, toWalks(options), options.options().size() < query.limit());
        } catch (NoRecentObservationException | ModelOutOfScopeException | ServiceUnavailableException
                 | BoardingOptionsException | DataAccessException unavailable) {
            return Situation.missingStay();
        }
    }

    private BoardingOptionsQuery walkQuery(RouteId routeId, String stationId, int stopSequence) {
        return new BoardingOptionsQuery(routeId, stationId, stopSequence, null, Set.of(AccessMode.WALK),
            maxWalkMinutes(), BoardingOptionsQuery.MAX_LIMIT,
            EnumSet.of(StopRelation.BEFORE, StopRelation.AFTER), true);
    }

    private int maxWalkMinutes() {
        long minutes = (walkCap.limit().toMillis() + 59_999L) / 60_000L;
        return (int) Math.min(BoardingOptionsQuery.MAX_MINUTES, Math.max(1L, minutes));
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

    private static List<Double> departureFullProbabilities(Board board, String stationId, int stopSequence) {
        List<StopState> matched = board.stops().stream()
            .filter(stop -> stop.sequence() == stopSequence && stationId.equals(stop.stopId()))
            .toList();
        if (matched.size() != 1) {
            return List.of();
        }
        List<Double> probabilities = new ArrayList<>();
        for (ApproachingVehicle vehicle : matched.getFirst().approachingVehicles()) {
            if (!(vehicle.forecast() instanceof VehicleForecast.Available available)) {
                continue;
            }
            if (!DEPARTURE_ZERO.equals(available.probabilityTarget())) {
                continue;
            }
            Double probability = available.departureFullProbability();
            if (probability == null || !Double.isFinite(probability) || probability < 0 || probability > 1) {
                return List.of();
            }
            probabilities.add(probability);
        }
        return probabilities;
    }
}
