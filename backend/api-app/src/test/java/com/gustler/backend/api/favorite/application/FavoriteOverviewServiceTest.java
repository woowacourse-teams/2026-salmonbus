package com.gustler.backend.api.favorite.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.api.board.application.BoardOverview;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.favorite.FavoriteGroupNotFoundException;
import com.gustler.backend.api.favorite.application.FavoriteOverview;
import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.FavoriteGroupName;
import com.gustler.backend.api.favorite.domain.FavoriteId;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FavoriteOverviewServiceTest {
    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final String ROUTE_ID = "204000057";
    private static final String STOP_ID = "277102322";
    private final MemoryRepository repository = new MemoryRepository();
    private final DefaultFavoriteGroupService defaultGroups = new DefaultFavoriteGroupService(repository);
    private final BoardQueryService boards = mock(BoardQueryService.class);
    private final ArrivalQueryService arrivals = mock(ArrivalQueryService.class);
    private final FavoriteOverviewService service = new FavoriteOverviewService(defaultGroups, boards, arrivals,
        Runnable::run);

    @Test
    void 두_정류장의_도착_조회를_함께_시작하고_모두_끝나야_순서대로_응답한다() throws Exception {
        Favorite first = favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP);
        Favorite second = favorite(ROUTE_ID, "277102323", FavoriteDirection.UP);
        repository.groups = groups(first, second);
        when(boards.getBoard(any())).thenReturn(new BoardOverview(boardWithStops(ROUTE_ID, List.of(
            stop(STOP_ID, 1, BoardDirection.UP, 3),
            stop(second.target().stopId(), 2, BoardDirection.UP, 3))), Duration.ZERO));
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch secondFinished = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(arrivals.get(any())).thenAnswer(invocation -> {
            ArrivalQuery query = invocation.getArgument(0);
            bothStarted.countDown();
            if (query.stationId().equals(STOP_ID)) {
                if (!releaseFirst.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("첫 정류장의 대기 해제가 누락됐다");
                }
            } else {
                secondFinished.countDown();
            }
            return null;
        });
        try (var executor = Executors.newFixedThreadPool(2);
             var caller = Executors.newSingleThreadExecutor()) {
            FavoriteOverviewService concurrent = new FavoriteOverviewService(defaultGroups, boards, arrivals, executor);
            CompletableFuture<FavoriteOverview> response = CompletableFuture.supplyAsync(
                () -> concurrent.get(MEMBER_ID), caller);
            try {
                assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(secondFinished.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(response.isDone()).isFalse();
            } finally {
                releaseFirst.countDown();
            }
            List<FavoriteOverview.Item> items = response.get(2, TimeUnit.SECONDS)
                .groups().getFirst().favorites();
            assertThat(items).extracting(FavoriteOverview.Item::id)
                .containsExactly(first.id().value(), second.id().value());
            assertThat(items).allSatisfy(item ->
                assertThat(item.board().stop().approachingVehicles()).hasSize(3));
            verify(boards).getBoard(any());
        }
    }

    @Test
    void 여러_그룹의_같은_대상은_한_번만_조회한다() {
        Favorite favorite = favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP);
        repository.groups = new MemberFavoriteGroups(List.of(
            group(UUID.randomUUID(), "출근", favorite),
            group(UUID.randomUUID(), "퇴근", favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP))));
        when(boards.getBoard(any())).thenReturn(new BoardOverview(board(ROUTE_ID, STOP_ID, 3), Duration.ZERO));
        when(arrivals.get(any())).thenThrow(new IllegalStateException("수집 Worker 불가"));

        FavoriteOverview overview = service.get(MEMBER_ID);

        assertThat(overview.groups()).hasSize(2);
        verify(boards, times(1)).getBoard(any());
        verify(arrivals, times(1)).get(any());
    }

    @Test
    void 실행기가_포화되어도_요청_스레드에서_조회한다() {
        repository.groups = groups(favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP));
        when(boards.getBoard(any())).thenReturn(new BoardOverview(board(ROUTE_ID, STOP_ID, 3), Duration.ZERO));
        Thread requestThread = Thread.currentThread();
        when(arrivals.get(any())).thenAnswer(invocation -> {
            assertThat(Thread.currentThread()).isSameAs(requestThread);
            return null;
        });
        FavoriteOverviewService saturated = new FavoriteOverviewService(defaultGroups, boards, arrivals,
            task -> { throw new RejectedExecutionException("실행기 포화"); });

        FavoriteOverview.Item item = saturated.get(MEMBER_ID).groups().getFirst().favorites().getFirst();

        assertThat(item.board().stop().approachingVehicles()).hasSize(3);
        verify(arrivals).get(any());
    }

    @Test
    void 도착_조회_실패에도_세_차량과_좌석을_유지한다() {
        repository.groups = groups(favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP));
        when(boards.getBoard(any())).thenReturn(new BoardOverview(board(ROUTE_ID, STOP_ID, 3), Duration.ZERO));
        when(arrivals.get(any())).thenThrow(new IllegalStateException("도착 조회 실패"));

        List<FavoriteOverview.Vehicle> vehicles = service.get(MEMBER_ID).groups().getFirst()
            .favorites().getFirst().board().stop().approachingVehicles();

        assertThat(vehicles).extracting(vehicle -> vehicle.watch().vehicleId())
            .containsExactly("vehicle-0", "vehicle-1", "vehicle-2");
        assertThat(vehicles).allSatisfy(vehicle ->
            assertThat(vehicle.watch().forecast()).isEqualTo(new VehicleForecast.Available(0.8, 12.0)));
        assertThat(vehicles.getFirst().eta().reason()).isEqualTo(FavoriteOverview.EtaReason.ARRIVAL_UNAVAILABLE);
        assertThat(vehicles.get(2).eta().reason()).isEqualTo(FavoriteOverview.EtaReason.ARRIVAL_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vehicle-0", "vehicle-2"})
    void 표시_순서와_관계없이_원천과_차량_ID가_일치할_때만_ETA를_연결한다(String providedVehicleId) {
        repository.groups = groups(favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP));
        when(boards.getBoard(any())).thenReturn(new BoardOverview(board(ROUTE_ID, STOP_ID, 3), Duration.ZERO));
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        ArrivalVisit visit = new ArrivalVisit(ROUTE_ID, ROUTE_ID, 1, 1, "UP", now.minusSeconds(60));
        Prediction source = new Prediction(providedVehicleId, 1, 180, null, 3, null,
            "NOT_REPORTED", null, null, null);
        when(arrivals.get(any())).thenReturn(new StationArrivalsOverview(STOP_ID, now,
            ArrivalAvailability.READY, null, null, null,
            List.of(new RouteArrivals(ROUTE_ID, "노선", visit, 1,
                List.of(ArrivalPrediction.at(source, now, now)))), Duration.ZERO));

        List<FavoriteOverview.Vehicle> vehicles = service.get(MEMBER_ID).groups().getFirst()
            .favorites().getFirst().board().stop().approachingVehicles();

        // 원천 ordinal 1/2와 watch 표시 순서는 다를 수 있으므로 차량 ID로 연결한다.
        assertThat(vehicles).hasSize(3);
        assertThat(vehicles.get(2).watch().vehicleId()).isEqualTo("vehicle-2");
        assertThat(vehicles).filteredOn(vehicle -> vehicle.watch().vehicleId().equals(providedVehicleId))
            .singleElement().satisfies(vehicle -> {
                assertThat(vehicle.eta().status()).isEqualTo(FavoriteOverview.EtaStatus.AVAILABLE);
                assertThat(vehicle.eta().remainingSeconds()).isEqualTo(180);
            });
        assertThat(vehicles).filteredOn(vehicle -> !vehicle.watch().vehicleId().equals(providedVehicleId))
            .allSatisfy(vehicle -> assertThat(vehicle.eta().reason())
                .isEqualTo(FavoriteOverview.EtaReason.VEHICLE_NOT_MATCHED));
    }

    @Test
    void 한_정류장_도착_조회가_실패해도_다른_정류장의_ETA를_유지한다() {
        Favorite first = favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP);
        Favorite second = favorite(ROUTE_ID, "277102323", FavoriteDirection.UP);
        repository.groups = groups(first, second);
        when(boards.getBoard(any())).thenReturn(new BoardOverview(boardWithStops(ROUTE_ID, List.of(
            stop(STOP_ID, 1, BoardDirection.UP, 3),
            stop(second.target().stopId(), 2, BoardDirection.UP, 3))), Duration.ZERO));
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        ArrivalVisit visit = new ArrivalVisit(ROUTE_ID, ROUTE_ID, 1, 2, "UP", now.minusSeconds(60));
        Prediction source = new Prediction("vehicle-0", 1, 180, null, 1, null,
            "NOT_REPORTED", null, null, null);
        when(arrivals.get(any())).thenAnswer(invocation -> {
            ArrivalQuery query = invocation.getArgument(0);
            if (query.stationId().equals(STOP_ID)) {
                throw new IllegalStateException("첫 정류장 도착 조회 실패");
            }
            return new StationArrivalsOverview(second.target().stopId(), now,
                ArrivalAvailability.READY, null, null, null,
                List.of(new RouteArrivals(ROUTE_ID, "노선", visit, 2,
                    List.of(ArrivalPrediction.at(source, now, now)))), Duration.ZERO);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            FavoriteOverviewService concurrent = new FavoriteOverviewService(defaultGroups, boards, arrivals, executor);

            List<FavoriteOverview.Item> items = concurrent.get(MEMBER_ID).groups().getFirst().favorites();

            assertThat(items).allSatisfy(item ->
                assertThat(item.board().stop().approachingVehicles()).hasSize(3));
            assertThat(items.getFirst().board().stop().approachingVehicles().getFirst().eta().reason())
                .isEqualTo(FavoriteOverview.EtaReason.ARRIVAL_UNAVAILABLE);
            assertThat(items.get(1).board().stop().approachingVehicles().getFirst().eta().status())
                .isEqualTo(FavoriteOverview.EtaStatus.AVAILABLE);
        }
    }

    @Test
    void 그룹과_즐겨찾기_순서대로_최대_세_대의_노선_정류장_항목을_만든다() {
        repository.groups = groups(favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP));
        when(boards.getBoard(any())).thenReturn(new BoardOverview(board(ROUTE_ID, STOP_ID, 4), Duration.ZERO));

        FavoriteOverview.Item item = service.get(MEMBER_ID).groups().getFirst().favorites().getFirst();

        assertThat(item.status()).isEqualTo(FavoriteOverview.ItemStatus.READY);
        assertThat(item.target()).isEqualTo(new FavoriteTarget(ROUTE_ID, STOP_ID, FavoriteDirection.UP));
        assertThat(item.board().stop().stopId()).isEqualTo(STOP_ID);
        assertThat(item.board().stop().approachingVehicles()).hasSize(3);
        assertThat(item.board().stop().approachingVehicles().getFirst().watch().remainingStops()).isEqualTo(1);
        assertThat(item.board().stop().approachingVehicles().get(2).eta().reason())
            .isEqualTo(FavoriteOverview.EtaReason.ARRIVAL_UNAVAILABLE);
    }

    @Test
    void 현재_방문이_여러_개면_항목을_AMBIGUOUS로_유지한다() {
        repository.groups = groups(favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP));
        Board board = boardWithStops(ROUTE_ID, List.of(
            stop(STOP_ID, 1, BoardDirection.UP, 0),
            stop(STOP_ID, 8, BoardDirection.UP, 0)));
        when(boards.getBoard(any())).thenReturn(new BoardOverview(board, Duration.ZERO));

        FavoriteOverview.Item item = service.get(MEMBER_ID).groups().getFirst().favorites().getFirst();

        assertThat(item.status()).isEqualTo(FavoriteOverview.ItemStatus.AMBIGUOUS);
        assertThat(item.board()).isNull();
    }

    @Test
    void 한_항목_조회_실패가_다른_항목을_숨기지_않는다() {
        Favorite broken = favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP);
        Favorite available = favorite("204000058", "277102323", FavoriteDirection.UP);
        repository.groups = groups(broken, available);
        when(boards.getBoard(any())).thenAnswer(invocation -> {
            String routeId = invocation.getArgument(0, RouteId.class).value();
            if (routeId.equals(ROUTE_ID)) {
                throw new IllegalStateException("조회 실패");
            }
            return new BoardOverview(board(routeId, available.target().stopId(), 1), Duration.ZERO);
        });

        List<FavoriteOverview.Item> items = service.get(MEMBER_ID).groups().getFirst().favorites();

        assertThat(items).extracting(FavoriteOverview.Item::status)
            .containsExactly(FavoriteOverview.ItemStatus.ERROR, FavoriteOverview.ItemStatus.READY);
    }

    @Test
    void 그룹을_지정하면_해당_그룹의_항목만_조회한다() {
        UUID selectedGroupId = UUID.randomUUID();
        Favorite selected = favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP);
        Favorite ignored = favorite("204000058", "277102323", FavoriteDirection.DOWN);
        repository.groups = new MemberFavoriteGroups(List.of(
            group(selectedGroupId, "출근", selected),
            group(UUID.randomUUID(), "퇴근", ignored)));
        when(boards.getBoard(any())).thenReturn(new BoardOverview(board(ROUTE_ID, STOP_ID, 1), Duration.ZERO));

        FavoriteOverview overview = service.get(MEMBER_ID, selectedGroupId);

        assertThat(overview.groups()).singleElement()
            .satisfies(group -> {
                assertThat(group.id()).isEqualTo(selectedGroupId);
                assertThat(group.favorites()).extracting(FavoriteOverview.Item::target)
                    .containsExactly(selected.target());
            });
        verify(boards).getBoard(argThat(route -> route.value().equals(ROUTE_ID)));
        verify(boards, never()).getBoard(argThat(route -> route.value().equals(ignored.target().routeId())));
    }

    @Test
    void 소유하지_않은_그룹을_지정하면_조회하지_않는다() {
        repository.groups = groups(favorite(ROUTE_ID, STOP_ID, FavoriteDirection.UP));

        assertThatThrownBy(() -> service.get(MEMBER_ID, UUID.randomUUID()))
            .isInstanceOf(FavoriteGroupNotFoundException.class);
        verifyNoInteractions(boards, arrivals);
    }

    private static MemberFavoriteGroups groups(Favorite... favorites) {
        return new MemberFavoriteGroups(List.of(group(UUID.randomUUID(), "출근", favorites)));
    }

    private static FavoriteGroup group(UUID id, String name, Favorite... favorites) {
        return new FavoriteGroup(new FavoriteGroupId(id), new FavoriteGroupName(name), List.of(favorites));
    }

    private static Favorite favorite(String routeId, String stopId, FavoriteDirection direction) {
        return new Favorite(new FavoriteId(UUID.randomUUID()), new FavoriteTarget(routeId, stopId, direction));
    }

    private static Board board(String routeId, String stopId, final int vehicleCount) {
        return boardWithStops(routeId, List.of(stop(stopId, 1, BoardDirection.UP, vehicleCount)));
    }

    private static Board boardWithStops(String routeId, List<StopState> stops) {
        OffsetDateTime now = OffsetDateTime.now();
        BoardRoute route = new BoardRoute(routeId, "노선", "기점", "종점", RouteStatus.FORECAST_READY,
            null, List.of(), "1", RouteHeadways.none());
        return new Board(route, now, now.plusMinutes(1), new ForecastModel(1, "model", now), 3, stops);
    }

    private static StopState stop(String stopId, final int sequence, BoardDirection direction,
        final int vehicleCount) {
        List<ApproachingVehicle> vehicles = IntStream.range(0, vehicleCount)
            .mapToObj(index -> new ApproachingVehicle("vehicle-" + index, index + 1,
                new VehicleForecast.Available(0.8, 12.0)))
            .toList();
        return new StopState(sequence, stopId, "강남역", direction, true, vehicles);
    }

    private static final class MemoryRepository implements FavoriteGroupRepository {
        private MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of());

        @Override
        public Optional<MemberFavoriteGroups> findByMemberPublicId(UUID memberPublicId) {
            return Optional.of(groups);
        }

        @Override
        public Optional<MemberFavoriteGroups> ensureDefault(UUID memberPublicId, FavoriteGroup defaultGroup) {
            if (groups.values().isEmpty()) {
                groups = new MemberFavoriteGroups(List.of(defaultGroup));
            }
            return Optional.of(groups);
        }

        @Override
        public Optional<MemberFavoriteGroups> update(UUID memberPublicId, UnaryOperator<MemberFavoriteGroups> change) {
            throw new UnsupportedOperationException();
        }
    }
}
