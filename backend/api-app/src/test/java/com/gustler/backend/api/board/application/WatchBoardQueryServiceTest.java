package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.DirectionInfo;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.domain.RouteStatus;
import com.gustler.backend.api.vehicle.application.VehicleQueryRepository;
import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import com.gustler.backend.api.vehicle.domain.VehicleDirection;
import com.gustler.backend.api.vehicle.domain.VehiclePhase;
import com.gustler.backend.api.vehicle.domain.VehicleSeat;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WatchBoardQueryServiceTest {

    private static final RouteId ROUTE_ID = new RouteId("204000057");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-27T12:00:00+09:00");
    private static final long BATCH_ID = 100L;

    @Mock
    private BoardQueryService boardQueryService;

    @Mock
    private VehicleQueryRepository vehicleQueryRepository;

    private WatchBoardQueryService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneId.of("Asia/Seoul"));
        service = new WatchBoardQueryService(boardQueryService, vehicleQueryRepository, clock);
    }

    @Test
    void 보드가_고른_관측_묶음의_차량을_붙여_돌려준다() {
        // given
        Board board = board(NOW.minusMinutes(1), 1);
        List<ObservedVehicle> vehicles = List.of(vehicle("V1"));
        given(boardQueryService.readBoard(ROUTE_ID)).willReturn(new BoardReading(board, BATCH_ID, Duration.ofSeconds(20)));
        given(vehicleQueryRepository.findVehicles(BATCH_ID)).willReturn(vehicles);

        // when
        WatchBoardOverview overview = service.getWatchBoard(ROUTE_ID);

        // then
        assertThat(overview.board()).isEqualTo(board);
        assertThat(overview.vehicles()).isEqualTo(vehicles);
        assertThat(overview.cacheMaxAge()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void 만료까지_남은_시간이_캐시_정책보다_짧으면_남은_시간만큼만_캐시한다() {
        // given
        Board board = board(NOW.minusMinutes(9), 0);
        given(boardQueryService.readBoard(ROUTE_ID)).willReturn(new BoardReading(board, BATCH_ID, Duration.ofSeconds(600)));
        given(vehicleQueryRepository.findVehicles(BATCH_ID)).willReturn(List.of());

        // when
        WatchBoardOverview overview = service.getWatchBoard(ROUTE_ID);

        // then
        assertThat(overview.cacheMaxAge()).isEqualTo(Duration.between(NOW, board.staleAt()));
    }

    @Test
    void 만료_시각이면_캐시하지_않는다() {
        // given
        Board board = board(NOW.minusMinutes(10), 0);
        given(boardQueryService.readBoard(ROUTE_ID)).willReturn(new BoardReading(board, BATCH_ID, Duration.ofSeconds(600)));
        given(vehicleQueryRepository.findVehicles(BATCH_ID)).willReturn(List.of());

        // when
        WatchBoardOverview overview = service.getWatchBoard(ROUTE_ID);

        // then
        assertThat(overview.cacheMaxAge()).isEqualTo(Duration.ZERO);
    }

    @Test
    void 판정_직후_만료_시각을_넘겨도_캐시_시간은_음수가_되지_않는다() {
        // given
        Board board = board(NOW.minusMinutes(10).minusSeconds(1), 0);
        given(boardQueryService.readBoard(ROUTE_ID)).willReturn(new BoardReading(board, BATCH_ID, Duration.ofSeconds(600)));
        given(vehicleQueryRepository.findVehicles(BATCH_ID)).willReturn(List.of());

        // when
        WatchBoardOverview overview = service.getWatchBoard(ROUTE_ID);

        // then
        assertThat(overview.cacheMaxAge()).isEqualTo(Duration.ZERO);
    }

    @Test
    void 보드를_읽지_못하면_같은_예외를_던지고_차량을_조회하지_않는다() {
        // given
        given(boardQueryService.readBoard(ROUTE_ID)).willThrow(new NoRecentObservationException());

        // when & then
        assertThatThrownBy(() -> service.getWatchBoard(ROUTE_ID)).isInstanceOf(NoRecentObservationException.class);
        verify(vehicleQueryRepository, never()).findVehicles(anyLong());
    }

    @Test
    void 차량_조회가_실패하면_빈_차량으로_응답하지_않고_예외를_던진다() {
        // given
        given(boardQueryService.readBoard(ROUTE_ID))
            .willReturn(new BoardReading(board(NOW.minusMinutes(1), 1), BATCH_ID, Duration.ofSeconds(20)));
        given(vehicleQueryRepository.findVehicles(BATCH_ID)).willThrow(new ServiceUnavailableException());

        // when & then
        assertThatThrownBy(() -> service.getWatchBoard(ROUTE_ID)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void 기록된_차량_수와_읽은_차량_수가_다르면_같은_묶음에_한_번만_경고한다(CapturedOutput output) {
        // given
        given(boardQueryService.readBoard(ROUTE_ID))
            .willReturn(new BoardReading(board(NOW.minusMinutes(1), 2), BATCH_ID, Duration.ofSeconds(20)));
        given(vehicleQueryRepository.findVehicles(BATCH_ID)).willReturn(List.of(vehicle("V1")));

        // when
        service.getWatchBoard(ROUTE_ID);
        service.getWatchBoard(ROUTE_ID);

        // then
        assertThat(output.getOut().split("기준 관측의 차량 수와 읽어낸 차량 수가 다르다", -1)).hasSize(2);
    }

    @Test
    void 묶음이_바뀌면_다시_경고하고_노선을_번갈아_조회해도_묶음마다_한_번만_경고한다(CapturedOutput output) {
        // given
        RouteId otherRoute = new RouteId("234000050");
        given(boardQueryService.readBoard(ROUTE_ID))
            .willReturn(new BoardReading(board(NOW.minusMinutes(1), 2), BATCH_ID, Duration.ofSeconds(20)))
            .willReturn(new BoardReading(board(NOW.minusMinutes(1), 2), BATCH_ID, Duration.ofSeconds(20)))
            .willReturn(new BoardReading(board(NOW.minusMinutes(1), 2), BATCH_ID + 1, Duration.ofSeconds(20)));
        given(boardQueryService.readBoard(otherRoute))
            .willReturn(new BoardReading(board(NOW.minusMinutes(1), 2), 200L, Duration.ofSeconds(20)));
        given(vehicleQueryRepository.findVehicles(anyLong())).willReturn(List.of(vehicle("V1")));

        // when
        service.getWatchBoard(ROUTE_ID);
        service.getWatchBoard(otherRoute);
        service.getWatchBoard(ROUTE_ID);
        service.getWatchBoard(otherRoute);
        service.getWatchBoard(ROUTE_ID);

        // then
        assertThat(output.getOut().split("기준 관측의 차량 수와 읽어낸 차량 수가 다르다", -1)).hasSize(4);
    }

    @Test
    void 보드와_차량을_한_스냅샷에서_읽도록_반복_읽기_트랜잭션을_선언한다() {
        // when
        Transactional transactional = WatchBoardQueryService.class.getAnnotation(Transactional.class);

        // then
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private static Board board(OffsetDateTime observedAt, final int vehiclesInService) {
        BoardRoute route = new BoardRoute("204000057", "3330", "기점", "종점", RouteStatus.FORECAST_READY, null,
            List.of(new DirectionInfo(BoardDirection.UP, "종점 방면", "기점", "종점", "04:50", "23:30")), "1",
            RouteHeadways.none());
        StopState stop = new StopState(1, "STOP-1", "기점", BoardDirection.UP, true, List.of());
        return new Board(route, observedAt, observedAt.plusMinutes(10), new ForecastModel(7L, "model-7", NOW.minusDays(1)),
            vehiclesInService, List.of(stop));
    }

    private static ObservedVehicle vehicle(String vehicleId) {
        return new ObservedVehicle(vehicleId, VehicleDirection.UP, 1, "STOP-1", "기점", VehiclePhase.DEPARTED,
            new VehicleSeat.Exact(7));
    }
}
