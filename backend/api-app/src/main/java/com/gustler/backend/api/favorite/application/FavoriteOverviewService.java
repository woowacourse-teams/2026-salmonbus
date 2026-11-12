package com.gustler.backend.api.favorite.application;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.api.board.application.BoardBatchRead;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.application.BoardReadDeadline;
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
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class FavoriteOverviewService {
    private static final Logger log = LoggerFactory.getLogger(FavoriteOverviewService.class);
    private final DefaultFavoriteGroupService defaultGroupService;
    private final BoardQueryService boards;
    private final ArrivalQueryService arrivals;
    private final Executor arrivalExecutor;

    public FavoriteOverviewService(DefaultFavoriteGroupService defaultGroupService, BoardQueryService boards,
        ArrivalQueryService arrivals, @Qualifier("favoriteArrivalExecutor") Executor arrivalExecutor) {
        this.defaultGroupService = defaultGroupService;
        this.boards = boards;
        this.arrivals = arrivals;
        this.arrivalExecutor = arrivalExecutor;
    }

    public FavoriteOverview get(UUID memberPublicId) {
        return get(memberPublicId, null);
    }

    public FavoriteOverview get(UUID memberPublicId, UUID groupId) {
        MemberFavoriteGroups owned = defaultGroupService.ensure(memberPublicId);
        Map<String, CompletableFuture<StationArrivalsOverview>> loadedArrivals = new HashMap<>();
        List<FavoriteGroup> selectedGroups = selectedGroups(owned, groupId);
        Map<String, BoardLoad> loadedBoards = loadBoards(selectedGroups);
        // 요청 내 중복 제거 Map은 요청 스레드에서만 다룬다. 모두 제출한 뒤 저장 순서로 결합한다.
        List<PendingGroup> pending = selectedGroups.stream()
            .map(group -> new PendingGroup(
                group,
                group.favorites().stream()
                .map(favorite -> item(favorite, loadedBoards, loadedArrivals))
                .toList()
            ))
            .toList();
        List<FavoriteOverview.Group> groups = pending.stream()
            .map(group -> new FavoriteOverview.Group(group.group().id().value(), group.group().name().value(),
                group.items().stream().map(CompletableFuture::join).toList()))
            .toList();
        return new FavoriteOverview(groups);
    }

    private CompletableFuture<FavoriteOverview.Item> item(Favorite favorite, Map<String, BoardLoad> loadedBoards,
        Map<String, CompletableFuture<StationArrivalsOverview>> loadedArrivals) {
        String routeId = favorite.target().routeId();
        String stopId = favorite.target().stopId();
        BoardLoad load = loadedBoards.get(routeId);
        if (load.board() == null) {
            return CompletableFuture.completedFuture(unavailable(favorite, load.status(), load.reason()));
        }
        Board board = load.board();
        List<StopState> matchingStops = board.stops().stream()
            .filter(StopState::boardingAllowed)
            .filter(stop -> stop.stopId().equals(stopId))
            .filter(stop -> stop.direction().name().equals(favorite.target().direction().name()))
            .toList();
        if (matchingStops.isEmpty()) {
            return CompletableFuture.completedFuture(unavailable(
                favorite,
                FavoriteOverview.ItemStatus.UNAVAILABLE,
                FavoriteOverview.ItemReason.TARGET_NOT_IN_CURRENT_ROUTE
            ));
        }
        if (matchingStops.size() > 1) {
            return CompletableFuture.completedFuture(unavailable(
                favorite,
                FavoriteOverview.ItemStatus.AMBIGUOUS,
                FavoriteOverview.ItemReason.MULTIPLE_CURRENT_VISITS
            ));
        }
        StopState stop = matchingStops.getFirst();
        List<ApproachingVehicle> approaching = stop.approachingVehicles().stream().limit(3).toList();
        FavoriteOverview.ItemStatus status = statusOf(board, approaching);
        CompletableFuture<StationArrivalsOverview> arrival = approaching.isEmpty()
            ? CompletableFuture.completedFuture(null)
            : loadedArrivals.computeIfAbsent(arrivalKey(board, stop), ignored -> loadArrival(board, stop));
        return arrival.thenApply(result -> itemWithArrival(favorite, board, stop, approaching, status, result));
    }

    private FavoriteOverview.Item itemWithArrival(Favorite favorite, Board board, StopState stop,
        List<ApproachingVehicle> approaching, FavoriteOverview.ItemStatus status, StationArrivalsOverview arrival) {
        List<FavoriteOverview.Vehicle> vehicles = vehicles(board, stop, approaching, arrival);
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
        StationArrivalsOverview arrival) {
        return approaching.stream()
            .map(source -> vehicle(source, arrival, board.route().id(), stop.sequence()))
            .toList();
    }

    private static String arrivalKey(Board board, StopState stop) {
        return board.route().id() + ":" + stop.stopId() + ":" + stop.sequence();
    }

    private CompletableFuture<StationArrivalsOverview> loadArrival(Board board, StopState stop) {
        try {
            return CompletableFuture.supplyAsync(() -> arrival(board, stop), arrivalExecutor);
        } catch (RejectedExecutionException full) {
            // 대기열을 늘리거나 ETA를 버리지 않고 기존 요청 스레드에서 처리한다.
            return CompletableFuture.completedFuture(arrival(board, stop));
        }
    }

    private StationArrivalsOverview arrival(Board board, StopState stop) {
        try {
            return arrivals.get(new ArrivalQuery(stop.stopId(), board.route().id(),
                Long.parseLong(board.route().referenceVersionId()), stop.sequence()));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private FavoriteOverview.Vehicle vehicle(ApproachingVehicle source,
        StationArrivalsOverview arrival, String routeId, final int stopSequence) {
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

    /**
     * 고른 그룹의 즐겨찾기 노선 판정보드를 한 번에 읽는다. 노선마다 실패를 따로 담고, 일괄 조회 자체가 실패하면
     * 모든 노선에 그 실패를 담고 로그는 한 번만 남긴다. 실패를 즐겨찾기 상태로 바꾸는 규칙은 노선을 하나씩 읽을 때와 같다.
     */
    private Map<String, BoardLoad> loadBoards(List<FavoriteGroup> groups) {
        List<String> routeIds = groups.stream()
            .flatMap(group -> group.favorites().stream())
            .map(favorite -> favorite.target().routeId())
            .distinct()
            .toList();
        Map<String, BoardLoad> loaded = new HashMap<>();
        if (routeIds.isEmpty()) {
            return loaded;
        }
        Map<String, BoardBatchRead> reads;
        try {
            reads = boards.readBoards(routeIds, BoardReadDeadline.none());
        } catch (ApiException failure) {
            routeIds.forEach(routeId -> loaded.put(routeId, failedBoardLoad(failure)));
            return loaded;
        } catch (RuntimeException failure) {
            log.warn("즐겨찾기 판정보드 일괄 조회에 실패했습니다. routes={}", routeIds.size(), failure);
            routeIds.forEach(routeId -> loaded.put(routeId, failedBoardLoad(null)));
            return loaded;
        }
        reads.forEach((routeId, read) -> loaded.put(routeId, boardLoadOf(routeId, read)));
        return loaded;
    }

    private BoardLoad boardLoadOf(String routeId, BoardBatchRead read) {
        if (read.reading() != null) {
            return new BoardLoad(read.reading().board(), null, null);
        }
        if (read.failure() instanceof ApiException exception) {
            return failedBoardLoad(exception);
        }
        if (read.failure() != null) {
            log.warn("즐겨찾기 판정보드 조회에 실패했습니다. routeId={}", routeId, read.failure());
        }
        return failedBoardLoad(null);
    }

    /**
     * 판정보드를 읽지 못한 노선의 상태다. {@code exception}이 null이면 API 오류가 아닌 실패다.
     */
    private static BoardLoad failedBoardLoad(ApiException exception) {
        if (exception == null) {
            return new BoardLoad(
                null,
                FavoriteOverview.ItemStatus.ERROR,
                FavoriteOverview.ItemReason.BOARD_UNAVAILABLE
            );
        }
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

    private record PendingGroup(FavoriteGroup group, List<CompletableFuture<FavoriteOverview.Item>> items) {
    }
}
