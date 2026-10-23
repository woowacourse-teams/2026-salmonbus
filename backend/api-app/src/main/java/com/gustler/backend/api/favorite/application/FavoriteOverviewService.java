package com.gustler.backend.api.favorite.application;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.api.board.application.BoardOverview;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.favorite.FavoriteGroupNotFoundException;
import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FavoriteOverviewService {
    private static final Logger log = LoggerFactory.getLogger(FavoriteOverviewService.class);
    private final DefaultFavoriteGroupService defaultGroupService;
    private final BoardQueryService boards;
    private final ArrivalQueryService arrivals;

    public FavoriteOverviewService(DefaultFavoriteGroupService defaultGroupService, BoardQueryService boards,
        ArrivalQueryService arrivals) {
        this.defaultGroupService = defaultGroupService;
        this.boards = boards;
        this.arrivals = arrivals;
    }

    public FavoriteOverview get(UUID memberPublicId) {
        return get(memberPublicId, null);
    }

    public FavoriteOverview get(UUID memberPublicId, UUID groupId) {
        MemberFavoriteGroups owned = defaultGroupService.ensure(memberPublicId);
        Map<String, BoardLoad> loadedBoards = new HashMap<>();
        Map<String, Optional<StationArrivalsOverview>> loadedArrivals = new HashMap<>();
        List<FavoriteGroup> selectedGroups = selectedGroups(owned, groupId);
        List<FavoriteOverview.Group> groups = selectedGroups.stream()
            .map(group -> new FavoriteOverview.Group(
                group.id().value(),
                group.name().value(),
                group.favorites().stream()
                .map(favorite -> item(favorite, loadedBoards, loadedArrivals))
                .toList()
            ))
            .toList();
        return new FavoriteOverview(groups);
    }

    private FavoriteOverview.Item item(Favorite favorite, Map<String, BoardLoad> loadedBoards,
        Map<String, Optional<StationArrivalsOverview>> loadedArrivals) {
        String routeId = favorite.target().routeId();
        String stopId = favorite.target().stopId();
        BoardLoad load = loadedBoards.computeIfAbsent(routeId, this::loadBoard);
        if (load.board() == null) {
            return unavailable(favorite, load.status(), load.reason());
        }
        Board board = load.board();
        List<StopState> matchingStops = board.stops().stream()
            .filter(StopState::boardingAllowed)
            .filter(stop -> stop.stopId().equals(stopId))
            .filter(stop -> stop.direction().name().equals(favorite.target().direction().name()))
            .toList();
        if (matchingStops.isEmpty()) {
            return unavailable(
                favorite,
                FavoriteOverview.ItemStatus.UNAVAILABLE,
                FavoriteOverview.ItemReason.TARGET_NOT_IN_CURRENT_ROUTE
            );
        }
        if (matchingStops.size() > 1) {
            return unavailable(
                favorite,
                FavoriteOverview.ItemStatus.AMBIGUOUS,
                FavoriteOverview.ItemReason.MULTIPLE_CURRENT_VISITS
            );
        }
        StopState stop = matchingStops.getFirst();
        List<ApproachingVehicle> approaching = stop.approachingVehicles().stream().limit(3).toList();
        FavoriteOverview.ItemStatus status = statusOf(board, approaching);
        List<FavoriteOverview.Vehicle> vehicles = vehicles(board, stop, approaching, loadedArrivals);
        FavoriteOverview.Stop selectedStop = new FavoriteOverview.Stop(
            stop.stopId(),
            stop.name(),
            stop.direction(),
            vehicles
        );
        FavoriteOverview.BoardSlice slice = new FavoriteOverview.BoardSlice(board.route(), selectedStop);
        return new FavoriteOverview.Item(favorite.id().value(), favorite.target(), status, null, slice);
    }

    private List<FavoriteOverview.Vehicle> vehicles(Board board, StopState stop, List<ApproachingVehicle> approaching,
        Map<String, Optional<StationArrivalsOverview>> loadedArrivals) {
        if (approaching.isEmpty()) {
            return List.of();
        }
        String key = board.route().id() + ":" + stop.stopId() + ":" + stop.sequence();
        StationArrivalsOverview arrival = loadedArrivals
            .computeIfAbsent(key, ignored -> Optional.ofNullable(arrival(board, stop)))
            .orElse(null);
        return IntStream.range(0, approaching.size())
            .mapToObj(index -> vehicle(approaching.get(index), index, arrival, board.route().id(), stop.sequence()))
            .toList();
    }

    private StationArrivalsOverview arrival(Board board, StopState stop) {
        try {
            return arrivals.get(new ArrivalQuery(stop.stopId(), board.route().id(),
                Long.parseLong(board.route().referenceVersionId()), stop.sequence()));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private FavoriteOverview.Vehicle vehicle(ApproachingVehicle source, final int index,
        StationArrivalsOverview arrival, String routeId, final int stopSequence) {
        if (index >= 2) {
            return new FavoriteOverview.Vehicle(
                source,
                FavoriteOverview.Eta.unavailable(FavoriteOverview.EtaReason.SOURCE_NOT_PROVIDED)
            );
        }
        FavoriteOverview.Eta eta = eta(source.vehicleId(), arrival, routeId, stopSequence);
        return new FavoriteOverview.Vehicle(source, eta);
    }

    private FavoriteOverview.Eta eta(
        String vehicleId,
        StationArrivalsOverview overview,
        String routeId,
        final int stopSequence
    ) {
        if (overview == null) {
            return FavoriteOverview.Eta.unavailable(FavoriteOverview.EtaReason.ARRIVAL_UNAVAILABLE);
        }
        ArrivalPrediction prediction = overview.routes().stream()
            .filter(route -> route.routeId().equals(routeId))
            .filter(route -> route.targetVisit() != null && route.targetVisit().stopSequence() == stopSequence)
            .map(RouteArrivals::arrivals)
            .flatMap(List::stream)
            .filter(candidate -> vehicleId != null && vehicleId.equals(candidate.source().vehicleId()))
            .findFirst()
            .orElse(null);
        if (prediction == null) {
            return FavoriteOverview.Eta.unavailable(FavoriteOverview.EtaReason.VEHICLE_NOT_MATCHED);
        }
        if (!prediction.available()) {
            return FavoriteOverview.Eta.unavailable(FavoriteOverview.EtaReason.PREDICTION_ELAPSED);
        }
        return FavoriteOverview.Eta.available(prediction.expectedArrivalAt(), prediction.remainingSeconds());
    }

    private BoardLoad loadBoard(String routeId) {
        try {
            BoardOverview overview = boards.getBoard(new RouteId(routeId));
            return new BoardLoad(overview.board(), null, null);
        } catch (ApiException exception) {
            if (exception.code() == ErrorCode.ROUTE_NOT_FOUND) {
                return new BoardLoad(
                    null,
                    FavoriteOverview.ItemStatus.UNAVAILABLE,
                    FavoriteOverview.ItemReason.TARGET_NOT_IN_CURRENT_ROUTE
                );
            }
            if (exception.code() == ErrorCode.MODEL_OUT_OF_SCOPE) {
                return new BoardLoad(
                    null,
                    FavoriteOverview.ItemStatus.PREPARING,
                    FavoriteOverview.ItemReason.MODEL_NOT_READY
                );
            }
            if (exception.code() == ErrorCode.NO_RECENT_OBSERVATION) {
                return new BoardLoad(
                    null,
                    FavoriteOverview.ItemStatus.UNAVAILABLE,
                    FavoriteOverview.ItemReason.OBSERVATION_UNAVAILABLE
                );
            }
            return new BoardLoad(
                null,
                FavoriteOverview.ItemStatus.ERROR,
                FavoriteOverview.ItemReason.BOARD_UNAVAILABLE
            );
        } catch (RuntimeException exception) {
            log.warn("즐겨찾기 판정보드 조회에 실패했습니다. routeId={}", routeId, exception);
            return new BoardLoad(
                null,
                FavoriteOverview.ItemStatus.ERROR,
                FavoriteOverview.ItemReason.BOARD_UNAVAILABLE
            );
        }
    }

    private static List<FavoriteGroup> selectedGroups(MemberFavoriteGroups owned, UUID groupId) {
        if (groupId == null) {
            return owned.values();
        }
        FavoriteGroup selected = owned.find(new FavoriteGroupId(groupId))
            .orElseThrow(FavoriteGroupNotFoundException::new);
        return List.of(selected);
    }

    private static FavoriteOverview.ItemStatus statusOf(Board board, List<ApproachingVehicle> approaching) {
        if (board.route().status() == RouteStatus.PREPARING) {
            return FavoriteOverview.ItemStatus.PREPARING;
        }
        if (approaching.isEmpty()) {
            return FavoriteOverview.ItemStatus.NO_VEHICLE;
        }
        return FavoriteOverview.ItemStatus.READY;
    }

    private static FavoriteOverview.Item unavailable(
        Favorite favorite,
        FavoriteOverview.ItemStatus status,
        FavoriteOverview.ItemReason reason
    ) {
        return new FavoriteOverview.Item(favorite.id().value(), favorite.target(), status, reason, null);
    }

    private record BoardLoad(
        Board board,
        FavoriteOverview.ItemStatus status,
        FavoriteOverview.ItemReason reason
    ) {
    }
}
