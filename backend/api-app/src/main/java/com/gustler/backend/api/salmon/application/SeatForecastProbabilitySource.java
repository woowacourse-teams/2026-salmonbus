package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.salmon.domain.Probabilities.ProbabilityInput;
import com.gustler.backend.api.salmon.domain.Probabilities.ProbabilitySupply;
import com.gustler.backend.api.salmon.domain.Probabilities.ValueOrigin;
import com.gustler.backend.api.salmon.domain.Probabilities.ValueTag;
import com.gustler.backend.api.salmon.domain.StopLists.StopEntry;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessException;

/**
 * 판정보드의 좌석 예보에서 도착 항목의 탑승 확률 근사를 만든다.
 *
 * <p>값은 1에서 출발 만석 확률을 뺀 값이고, 줄 맨 뒤에 서 있어도 탈 확률로 읽는다. 조건 이력 없는 주변확률로 내고
 * 출처를 SEAT_FORECAST로 남긴다. 확률 목표가 DEPARTURE_ZERO나 FIRST_POST_STOP_ZERO인 예보만 쓰고,
 * 예보를 찾지 못한 항목에는 입력을 만들지 않는다.
 */
public final class SeatForecastProbabilitySource {
    // 둘 다 목표 정류장을 떠난 뒤 다음 승차 정류장 전의 관측이 0석일 확률이다. 고르는 관측만 다르다(처음, 마지막).
    private static final Set<String> FULL_DEPARTURE_TARGETS = Set.of("DEPARTURE_ZERO", "FIRST_POST_STOP_ZERO");

    /**
     * 도착 항목에서 값을 만들지 못한 원인이다. 앞의 다섯은 목록 전체에 걸리고, 그 목록의 모든 항목에 같은 원인이 붙는다.
     * <ul>
     *   <li>LIST_WITHOUT_VEHICLE_ID: 목록에 차량 ID가 있는 항목이 하나도 없어 보드를 읽지 않았다.</li>
     *   <li>BOARD_READ_FAILED: 보드가 읽기를 거부했다. 거부한 오류 코드가 함께 온다.</li>
     *   <li>QUERY_FAILED: 보드나 정류장 방문의 DB 조회가 실패했다.</li>
     *   <li>BOARD_STALE: 판정 시각이 보드 관측의 만료 시각을 지났다.</li>
     *   <li>VISIT_NOT_MATCHED: 도착 방문을 찾지 못했거나 보드와 노선 판본이 다르다.</li>
     *   <li>ENTRY_WITHOUT_VEHICLE_ID: 이 항목에 차량 ID가 없다.</li>
     *   <li>VEHICLE_NOT_ON_BOARD: 보드의 그 정류장 방문에 이 차량이 없다.</li>
     *   <li>VEHICLE_AMBIGUOUS: 보드의 그 정류장 방문에 같은 차량 ID가 둘 이상이다.</li>
     *   <li>FORECAST_UNAVAILABLE: 보드가 이 차량의 예보를 제공하지 못했다.</li>
     *   <li>PROBABILITY_TARGET_NOT_USED: 예보의 확률 목표가 쓰는 목표가 아니거나 비어 있다.</li>
     * </ul>
     */
    public enum Cause { LIST_WITHOUT_VEHICLE_ID, BOARD_READ_FAILED, QUERY_FAILED, BOARD_STALE, VISIT_NOT_MATCHED,
        ENTRY_WITHOUT_VEHICLE_ID, VEHICLE_NOT_ON_BOARD, VEHICLE_AMBIGUOUS, FORECAST_UNAVAILABLE,
        PROBABILITY_TARGET_NOT_USED }

    public sealed interface Lookup permits Found, NotFound {
    }

    public record Found(double value, String probabilityTarget) implements Lookup {
        public Found {
            Objects.requireNonNull(probabilityTarget);
        }
    }

    public record NotFound(Cause cause, Optional<ErrorCode> boardError) implements Lookup {
        public NotFound {
            Objects.requireNonNull(cause);
            Objects.requireNonNull(boardError);
            if (boardError.isPresent() != (cause == Cause.BOARD_READ_FAILED)) {
                throw new IllegalArgumentException("boardError must be present exactly for BOARD_READ_FAILED");
            }
        }

        public NotFound(Cause cause) {
            this(cause, Optional.empty());
        }
    }

    public record EntryLookup(String entryId, Lookup lookup) {
    }

    private final BoardQueryService boards;
    private final ArrivalReadRepository arrivals;

    public SeatForecastProbabilitySource(BoardQueryService boards, ArrivalReadRepository arrivals) {
        this.boards = boards;
        this.arrivals = arrivals;
    }

    public ProbabilitySupply probabilitiesFor(String routeId, String stationId, int stopSequence, StopList list,
                                              Instant decisionAt) {
        List<ProbabilityInput> inputs = new ArrayList<>();
        for (EntryLookup entry : lookupsFor(routeId, stationId, stopSequence, list, decisionAt)) {
            if (entry.lookup() instanceof Found found) {
                inputs.add(new ProbabilityInput(entry.entryId(), ValueTag.MARG, Optional.empty(), found.value(),
                    ValueOrigin.SEAT_FORECAST));
            }
        }
        return new ProbabilitySupply(inputs);
    }

    /** 목록의 항목마다 순서대로 값 또는 값을 만들지 못한 원인을 돌려준다. */
    public List<EntryLookup> lookupsFor(String routeId, String stationId, int stopSequence, StopList list,
                                        Instant decisionAt) {
        if (list.entries().stream().noneMatch(entry -> entry.vehicleId().isPresent())) {
            return sameForAll(list, new NotFound(Cause.LIST_WITHOUT_VEHICLE_ID));
        }
        try {
            return lookupsOf(routeId, stationId, stopSequence, list, decisionAt);
        } catch (ApiException exception) {
            return sameForAll(list, new NotFound(Cause.BOARD_READ_FAILED, Optional.of(exception.code())));
        } catch (DataAccessException exception) {
            return sameForAll(list, new NotFound(Cause.QUERY_FAILED));
        }
    }

    private List<EntryLookup> lookupsOf(String routeId, String stationId, int stopSequence, StopList list,
                                        Instant decisionAt) {
        Board board = boards.readBoard(new RouteId(routeId)).board();
        // 보드는 조회 시각 기준으로 낡은 관측을 이미 거부한다. 여기서는 같은 만료 시각을 판정 시각에도 적용한다.
        if (decisionAt.isAfter(board.staleAt().toInstant())) {
            return sameForAll(list, new NotFound(Cause.BOARD_STALE));
        }
        if (!sameRouteVersion(board, routeId, stationId, stopSequence)) {
            return sameForAll(list, new NotFound(Cause.VISIT_NOT_MATCHED));
        }
        List<ApproachingVehicle> approaching = board.stops().stream()
            .filter(stop -> stop.sequence() == stopSequence)
            .flatMap(stop -> stop.approachingVehicles().stream())
            .toList();
        return list.entries().stream()
            .map(entry -> new EntryLookup(entry.id(), lookupOf(entry, approaching)))
            .toList();
    }

    // 좌석 예보의 목표 순번과 도착 방문의 순번은 같은 노선 판본 안에서만 같은 정류장 방문을 가리킨다.
    private boolean sameRouteVersion(Board board, String routeId, String stationId, int stopSequence) {
        return arrivals.visits(stationId).stream()
            .filter(visit -> visit.routeId().equals(routeId) && visit.stopSequence() == stopSequence)
            .findFirst()
            .filter(visit -> Long.toString(visit.referenceVersionId()).equals(board.route().referenceVersionId()))
            .isPresent();
    }

    private static Lookup lookupOf(StopEntry entry, List<ApproachingVehicle> approaching) {
        if (entry.vehicleId().isEmpty()) {
            return new NotFound(Cause.ENTRY_WITHOUT_VEHICLE_ID);
        }
        List<ApproachingVehicle> matched = approaching.stream()
            .filter(vehicle -> entry.vehicleId().get().equals(vehicle.vehicleId()))
            .toList();
        if (matched.isEmpty()) {
            return new NotFound(Cause.VEHICLE_NOT_ON_BOARD);
        }
        if (matched.size() > 1) {
            return new NotFound(Cause.VEHICLE_AMBIGUOUS);
        }
        if (!(matched.get(0).forecast() instanceof VehicleForecast.Available available)) {
            return new NotFound(Cause.FORECAST_UNAVAILABLE);
        }
        String target = available.probabilityTarget();
        if (target == null || !FULL_DEPARTURE_TARGETS.contains(target)) {
            return new NotFound(Cause.PROBABILITY_TARGET_NOT_USED);
        }
        // 보드는 두 확률 목표 모두에서 seatAvailableProbability에 1에서 만석 확률을 뺀 값을 싣는다.
        return new Found(available.seatAvailableProbability(), target);
    }

    private static List<EntryLookup> sameForAll(StopList list, NotFound notFound) {
        return list.entries().stream().map(entry -> new EntryLookup(entry.id(), notFound)).toList();
    }
}
