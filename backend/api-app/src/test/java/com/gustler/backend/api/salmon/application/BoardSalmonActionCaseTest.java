package com.gustler.backend.api.salmon.application;

import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.inSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.application.BoardReading;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.DirectionInfo;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.boarding.BoardingOptionsException;
import com.gustler.backend.api.boarding.application.BoardingOptionsQuery;
import com.gustler.backend.api.boarding.application.BoardingOptionsQueryService;
import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.BoardingOptions;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Access;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Leg;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Option;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.boarding.domain.StopVisit;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.route.domain.RouteStatus;
import com.gustler.backend.api.salmon.domain.IntegratedJudge;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Recommendation;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Terminal;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

class BoardSalmonActionCaseTest {
    private static final String ROUTE = "204000057";
    private static final RouteId ROUTE_ID = new RouteId(ROUTE);
    private static final String STATION = "205000231";
    private static final Instant OBSERVED = Instant.parse("2026-11-11T03:00:00Z");
    private static final Instant STALE = OBSERVED.plusSeconds(300);

    @Test
    void 만차확률_0_3이면_이동을_찾지_않고_같은_방향_도보만_묻는다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(reading(board(OBSERVED, STALE, full("bus", 0.3))));
        given(walks.get(any())).willReturn(noWalks());

        Terminal terminal = decide(cases(boards, walks, clock(OBSERVED)));

        assertThat(terminal).isEqualTo(Terminal.NO_MOVE_POLICY);
        then(boards).should().readBoard(ROUTE_ID);
        ArgumentCaptor<BoardingOptionsQuery> captor = ArgumentCaptor.forClass(BoardingOptionsQuery.class);
        then(walks).should().get(captor.capture());
        BoardingOptionsQuery query = captor.getValue();
        assertThat(query.maxMinutes()).isEqualTo(15);
        assertThat(query.limit()).isEqualTo(BoardingOptionsQuery.MAX_LIMIT);
        assertThat(query.modes()).containsExactly(AccessMode.WALK);
        assertThat(query.relations()).containsExactlyInAnyOrder(StopRelation.BEFORE, StopRelation.AFTER);
        assertThat(query.boardingOnly()).isTrue();
        assertThat(query.stationId()).isEqualTo(STATION);
        assertThat(query.stopSequence()).isEqualTo(5);
    }

    @Test
    void 만차확률_0_8이고_도보가_없으면_평가할_이동이_없다() {
        assertThat(decide(stable(full("bus", 0.8)))).isEqualTo(Terminal.NO_MOVE_NO_CANDIDATE);
    }

    @Test
    void 같은_방향_도보가_있어도_추천하지_않는다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(reading(board(OBSERVED, STALE, full("bus", 0.8))));
        given(walks.get(any())).willReturn(oneWalk(600));

        Recommendation recommendation = IntegratedJudge.decide(
            cases(boards, walks, clock(OBSERVED)).situation(ROUTE_ID, STATION, 5));

        assertThat(recommendation.terminal()).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(recommendation.chosenId()).isEmpty();
    }

    @Test
    void 마감_안에_닿는_두_대_가운데_한_대라도_만차확률이_낮으면_이동을_찾지_않는다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(
            reading(board(OBSERVED, STALE, full("bus-a", 0.8), full("bus-b", 0.2))));
        given(walks.get(any())).willReturn(noWalks());
        SalmonDueArrivals arrivals = arrivals(inSeconds("bus-a", 1, 300), inSeconds("bus-b", 2, 360));

        assertThat(decide(cases(boards, arrivals, walks, clock(OBSERVED)))).isEqualTo(Terminal.NO_MOVE_POLICY);
    }

    @Test
    void 마감_안에_닿는_두_대의_만차확률이_모두_높으면_이동_후보를_본다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(
            reading(board(OBSERVED, STALE, full("bus-a", 0.8), full("bus-b", 0.9))));
        given(walks.get(any())).willReturn(noWalks());
        SalmonDueArrivals arrivals = arrivals(inSeconds("bus-a", 1, 300), inSeconds("bus-b", 2, 360));

        assertThat(decide(cases(boards, arrivals, walks, clock(OBSERVED)))).isEqualTo(Terminal.NO_MOVE_NO_CANDIDATE);
    }

    @Test
    void 마감_안에_닿는_두_대_가운데_한_대의_만차확률을_모르면_근거_부족이다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(reading(board(OBSERVED, STALE, full("bus-a", 0.2))));
        SalmonDueArrivals arrivals = arrivals(inSeconds("bus-a", 1, 300), inSeconds("bus-b", 2, 360));

        assertThat(decide(cases(boards, arrivals, walks, clock(OBSERVED)))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        then(walks).should(never()).get(any());
    }

    @Test
    void 마감_뒤에_닿는_차량의_만차확률은_대기_위험으로_쓰지_않는다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(reading(board(OBSERVED, STALE, full("bus", 0.3))));
        SalmonDueArrivals arrivals = arrivals(inSeconds("bus", 1, 1500));

        assertThat(decide(cases(boards, arrivals, walks, clock(OBSERVED)))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        then(walks).should(never()).get(any());
    }

    @Test
    void 마감_안에_닿는_한_대의_만차확률만_대기_위험으로_쓴다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(
            reading(board(OBSERVED, STALE, full("bus-a", 0.3), full("bus-b", 0.9))));
        given(walks.get(any())).willReturn(noWalks());
        SalmonDueArrivals arrivals = arrivals(inSeconds("bus-a", 1, 300), inSeconds("bus-b", 2, 1500));

        assertThat(decide(cases(boards, arrivals, walks, clock(OBSERVED)))).isEqualTo(Terminal.NO_MOVE_POLICY);
    }

    @Test
    void 도착_스냅샷이_없거나_그_차량이_전광판에_없으면_근거_부족이다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(reading(board(OBSERVED, STALE, full("bus", 0.3))));

        assertThat(decide(cases(boards, SalmonArrivalFixture.noSnapshot(OBSERVED), walks, clock(OBSERVED))))
            .isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(decide(cases(boards, arrivals(inSeconds("other", 1, 300)), walks, clock(OBSERVED))))
            .isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        then(walks).should(never()).get(any());
    }

    @Test
    void 만료_시각을_지나면_근거_부족이다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(reading(board(OBSERVED, STALE, full("bus", 0.3))));

        assertThat(decide(cases(boards, walks, clock(STALE.plusMillis(1))))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        then(walks).should(never()).get(any());
    }

    @Test
    void 만료_시각과_같으면_그_확률을_쓴다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(reading(board(OBSERVED, STALE, full("bus", 0.3))));
        given(walks.get(any())).willReturn(noWalks());

        assertThat(decide(cases(boards, walks, clock(STALE)))).isEqualTo(Terminal.NO_MOVE_POLICY);
    }

    @Test
    void 보드를_못_읽으면_근거_부족이다() {
        List<RuntimeException> failures = List.of(
            new NoRecentObservationException(),
            new ModelOutOfScopeException(),
            new ServiceUnavailableException(),
            new DataAccessResourceFailureException("db"));
        for (RuntimeException failure : failures) {
            BoardQueryService boards = mock(BoardQueryService.class);
            BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
            given(boards.readBoard(ROUTE_ID)).willThrow(failure);

            assertThat(decide(cases(boards, walks, clock(OBSERVED)))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
            then(walks).should(never()).get(any());
        }
    }

    @Test
    void 노선이_없으면_그대로_던진다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willThrow(new RouteNotFoundException());

        assertThatThrownBy(() -> cases(boards, mock(BoardingOptionsQueryService.class), clock(OBSERVED))
            .situation(ROUTE_ID, STATION, 5)).isInstanceOf(RouteNotFoundException.class);
    }

    @Test
    void 도보_조회가_실패하면_근거_부족이다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(reading(board(OBSERVED, STALE, full("bus", 0.8))));
        given(walks.get(any())).willThrow(new BoardingOptionsException(ErrorCode.STATION_NOT_FOUND));

        assertThat(decide(cases(boards, walks, clock(OBSERVED)))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    @Test
    void 출발_만차확률이_아니면_근거_부족이다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        ApproachingVehicle firstPost = new ApproachingVehicle("bus", 1, new VehicleForecast.Available(0.7, 12.0));
        given(boards.readBoard(ROUTE_ID)).willReturn(reading(board(OBSERVED, STALE, firstPost)));

        assertThat(decide(cases(boards, walks, clock(OBSERVED)))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        then(walks).should(never()).get(any());
    }

    @Test
    void 목록의_두_대가_모두_만차일_것_같아도_이어_붙인_뒤_차량에_자리가_있을_것_같으면_이동을_찾지_않는다() {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(reading(SalmonArrivalFixture.board(OBSERVED.minusSeconds(30),
            STALE, fullAt("front", 3, 0.8), fullAt("second", 4, 0.9), fullAt("hidden", 8, 0.1))));
        given(walks.get(any())).willReturn(noWalks());
        SalmonArrivalFixture listedOnly = SalmonArrivalFixture.received(OBSERVED, OBSERVED.plusSeconds(3600))
            .stored(5, inSeconds("front", 1, 300), inSeconds("second", 2, 360));
        SalmonArrivalFixture withHidden = SalmonArrivalFixture.received(OBSERVED, OBSERVED.plusSeconds(3600))
            .stored(5, inSeconds("front", 1, 300), inSeconds("second", 2, 360))
            .collectable(2, inSeconds("hidden", 1, 240));

        assertThat(decide(cases(boards, listedOnly.reader(), walks, clock(OBSERVED))))
            .isEqualTo(Terminal.NO_MOVE_NO_CANDIDATE);
        assertThat(decide(cases(boards, withHidden.reader(), walks, clock(OBSERVED))))
            .isEqualTo(Terminal.NO_MOVE_POLICY);
    }

    private static Terminal decide(BoardSalmonActionCase cases) {
        return IntegratedJudge.decide(cases.situation(ROUTE_ID, STATION, 5)).terminal();
    }

    private static BoardSalmonActionCase stable(ApproachingVehicle... vehicles) {
        BoardQueryService boards = mock(BoardQueryService.class);
        BoardingOptionsQueryService walks = mock(BoardingOptionsQueryService.class);
        given(boards.readBoard(ROUTE_ID)).willReturn(reading(board(OBSERVED, STALE, vehicles)));
        given(walks.get(any())).willReturn(noWalks());
        return cases(boards, walks, clock(OBSERVED));
    }

    private static BoardSalmonActionCase cases(BoardQueryService boards, BoardingOptionsQueryService walks, Clock clock) {
        return cases(boards, arrivals(inSeconds("bus", 1, 600)), walks, clock);
    }

    private static BoardSalmonActionCase cases(BoardQueryService boards, SalmonDueArrivals arrivals,
                                               BoardingOptionsQueryService walks, Clock clock) {
        return new BoardSalmonActionCase(boards, arrivals, walks,
            new SalmonActionAssessment(SalmonSettingsFixture.adopted()), SalmonSettingsFixture.adopted(), clock);
    }

    private static SalmonDueArrivals arrivals(Prediction... predictions) {
        return SalmonArrivalFixture.arrivals(OBSERVED, OBSERVED.plusSeconds(3600), predictions);
    }

    private static Clock clock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static BoardReading reading(Board board) {
        return new BoardReading(board, 1L, Duration.ofSeconds(15));
    }

    private static Board board(Instant observedAt, Instant staleAt, ApproachingVehicle... vehicles) {
        BoardRoute route = new BoardRoute(ROUTE, "9300", "기점", "종점", RouteStatus.FORECAST_READY, null,
            List.of(new DirectionInfo(BoardDirection.UP, "종점 방면", "기점", "종점", "05:00", "22:30")),
            "31", RouteHeadways.none());
        return new Board(route, observedAt.atOffset(ZoneOffset.UTC), staleAt.atOffset(ZoneOffset.UTC),
            new ForecastModel(1, "release", observedAt.atOffset(ZoneOffset.UTC).minusDays(1)), 1,
            List.of(new StopState(5, STATION, "정류장", BoardDirection.UP, true, List.of(vehicles))));
    }

    private static ApproachingVehicle full(String vehicleId, double fullProbability) {
        return fullAt(vehicleId, 1, fullProbability);
    }

    private static ApproachingVehicle fullAt(String vehicleId, int remainingStops, double fullProbability) {
        return new ApproachingVehicle(vehicleId, remainingStops, new VehicleForecast.Available(1 - fullProbability,
            12.0, "AFTER_ALIGHTING_ESTIMATE", "DEPARTURE_ZERO", fullProbability));
    }

    private static BoardingOptions noWalks() {
        return new BoardingOptions(ROUTE, 1L, origin(), List.of());
    }

    private static BoardingOptions oneWalk(int seconds) {
        StopVisit target = new StopVisit("205000100", 3, "앞 정류장", "UP", true, null, null);
        Access access = new Access(seconds, List.of(new Leg(AccessMode.WALK, seconds, null, "walk")));
        return new BoardingOptions(ROUTE, 1L, origin(),
            List.of(new Option(target, -2, StopRelation.BEFORE, List.of(access))));
    }

    private static StopVisit origin() {
        return new StopVisit(STATION, 5, "기준", "UP", true, null, null);
    }
}
