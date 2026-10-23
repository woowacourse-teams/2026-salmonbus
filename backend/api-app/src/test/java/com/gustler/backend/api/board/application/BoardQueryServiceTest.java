package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.HeadwayRange;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast.Available;
import com.gustler.backend.api.board.domain.VehicleForecast.Unavailable;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BoardQueryServiceTest {
    private static final RouteId ROUTE_ID = new RouteId("204000057");
    private static final OffsetDateTime OBSERVED_AT = OffsetDateTime.parse(
        "2026-08-27T08:00:00+09:00"
    );
    private static final ForecastModel ACTIVE_MODEL = new ForecastModel(
        9L,
        "model-active",
        OffsetDateTime.parse("2026-08-26T23:59:59+09:00")
    );
    private static final ForecastModel SNAPSHOT_MODEL = new ForecastModel(
        7L,
        "model-snapshot",
        OffsetDateTime.parse("2026-08-25T23:59:59+09:00")
    );

    @Mock
    private BoardQueryRepository repository;

    private BoardQueryService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
            Instant.parse("2026-08-26T23:04:59Z"),
            ZoneId.of("Asia/Seoul")
        );
        service = new BoardQueryService(
            repository,
            new BoardFreshnessPolicy(clock),
            new BoardCachePolicy(clock)
        );
    }

    @Test
    void 관측_정보와_노선_방향으로_Board를_조립한다() {
        // given
        givenRoundTripBoard(List.of(prediction(3, "A", 1, 1, 0.2, 10.0)));

        BoardOverview overview = service.getBoard(ROUTE_ID);

        // when
        Board board = overview.board();

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(board.observedAt()).isEqualTo(OBSERVED_AT);
            softly.assertThat(board.vehiclesInService()).isEqualTo(4);
            softly.assertThat(overview.cacheMaxAge()).isEqualTo(Duration.ofSeconds(15));
        }
        assertThat(board.route().directions())
            .extracting(direction -> direction.name())
            .containsExactly("회차점 방면", "종점 방면");
    }

    @Test
    void 노선_버전의_요일별_배차간격을_노선_정보에_그대로_싣는다() {
        // given
        RouteHeadways headways = new RouteHeadways(
            new HeadwayRange(10, 20), null, new HeadwayRange(15, 30), new HeadwayRange(16, 35));
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(new BoardSnapshot(
            1L,
            route(),
            2,
            new DepartureSchedule("04:50", "23:30", "05:00", "23:30", headways),
            Optional.of(observation()),
            Optional.of(ACTIVE_MODEL)
        )));
        given(repository.findStops(1L)).willReturn(roundTripStops());
        given(repository.findPredictions(100L)).willReturn(List.of());
        given(repository.findObservedVehicles(100L, 1L)).willReturn(List.of());

        // when
        Board actual = service.getBoard(ROUTE_ID).board();

        // then
        assertThat(actual.route().headways()).isEqualTo(headways);
        assertThat(actual.route().directions())
            .extracting(direction -> direction.firstDepartureTime())
            .containsExactly("04:50", "05:00");
    }

    @Test
    void 보드를_읽으면_기준_관측_묶음의_ID와_같은_보드를_돌려준다() {
        // given
        givenRoundTripBoard(List.of(prediction(3, "A", 1, 1, 0.2, 10.0)));

        // when
        BoardReading reading = service.readBoard(ROUTE_ID);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(reading.observationBatchId()).isEqualTo(100L);
            softly.assertThat(reading.board()).isEqualTo(service.getBoard(ROUTE_ID).board());
            softly.assertThat(reading.cacheMaxAge()).isEqualTo(Duration.ofSeconds(15));
        }
    }

    @Test
    void 예보_행이_있으면_그_행의_모델을_사용한다() {
        // when
        givenRoundTripBoard(List.of(prediction(3, "A", 1, 1, 0.2, 10.0)));

        // then
        assertThat(service.getBoard(ROUTE_ID).board().model()).isEqualTo(SNAPSHOT_MODEL);
    }

    @Test
    void 접근_차량을_거리순으로_정렬해_정류장별_최대_3대를_조립한다() {
        // given
        givenRoundTripBoard(List.of(
            prediction(3, "C", 5, 3, 0.4, 12.0),
            prediction(3, null, 4, 1, 0.2, null),
            prediction(3, "A", 3, 2, 0.1, 20.0),
            prediction(3, "B", 2, 1, 0.3, 15.0),
            prediction(3, "D", 1, 1, 0.5, 18.0)
        ));

        // when
        StopState boardingStop = service.getBoard(ROUTE_ID).board().stops().get(2);

        // then
        assertThat(boardingStop.approachingVehicles())
            .extracting(ApproachingVehicle::vehicleId)
            .containsExactly("B", "D", null);
    }

    @Test
    void 만석_확률을_뒤집은_빈자리_확률이_소수점까지_그대로_나온다() {
        // given
        givenRoundTripBoard(List.of(prediction(3, "A", 1, 1, 0.004, 10.0)));

        // when
        ApproachingVehicle vehicle = service.getBoard(ROUTE_ID)
            .board()
            .stops()
            .get(2)
            .approachingVehicles()
            .getFirst();

        // then
        assertThat(((Available) vehicle.forecast()).seatAvailableProbability()).isEqualTo(0.996d);
    }

    @Test
    void 기대_잔여석이_없어도_예보_차량을_유지한다() {
        // given
        givenRoundTripBoard(List.of(prediction(3, "A", 1, 1, 0.2, null)));

        // when
        List<ApproachingVehicle> vehicles = service.getBoard(ROUTE_ID)
            .board()
            .stops()
            .get(2)
            .approachingVehicles();

        // then
        assertThat(vehicles).singleElement().satisfies(vehicle -> {
            assertThat(vehicle.vehicleId()).isEqualTo("A");
            assertThat(((Available) vehicle.forecast()).expectedSeats()).isNull();
        });
    }

    @Test
    void 기대_잔여석이_비유한값이어도_차량은_보드에_남는다() {
        // when
        givenRoundTripBoard(List.of(
            prediction(3, "INFINITE", 1, 1, 0.2, Double.POSITIVE_INFINITY),
            prediction(3, "VALID", 2, 2, 0.3, 10.0)
        ));

        // then
        assertThat(service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles())
            .extracting(ApproachingVehicle::vehicleId)
            .containsExactly("INFINITE", "VALID");
    }

    @Test
    void 기대_잔여석이_비유한값인_차량은_기대_잔여석만_비운다() {
        // when
        givenRoundTripBoard(List.of(
            prediction(3, "INFINITE", 1, 1, 0.2, Double.POSITIVE_INFINITY)
        ));

        // then
        assertThat(service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles())
            .singleElement()
            .satisfies(vehicle -> assertThat(((Available) vehicle.forecast()).expectedSeats()).isNull());
    }

    @Test
    void 기대_잔여석이_비유한값이어도_빈자리_확률은_그대로_낸다() {
        // when
        givenRoundTripBoard(List.of(
            prediction(3, "INFINITE", 1, 1, 0.2, Double.POSITIVE_INFINITY)
        ));

        // then
        assertThat(service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles())
            .singleElement()
            .satisfies(vehicle ->
                assertThat(((Available) vehicle.forecast()).seatAvailableProbability()).isEqualTo(0.8d));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
    void 만석_확률이_범위_밖이거나_비유한값이면_차량을_유지하고_예보만_미제공한다(final double invalidSeatFullChance) {
        // when
        givenRoundTripBoard(List.of(
            prediction(3, "INVALID", 1, 1, invalidSeatFullChance, 10.0),
            prediction(3, "VALID", 2, 2, 0.3, 10.0)
        ));

        // then
        assertThat(service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles())
            .extracting(ApproachingVehicle::vehicleId)
            .containsExactly("INVALID", "VALID");
        assertThat(service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles().getFirst().forecast())
            .isInstanceOf(Unavailable.class);
    }

    @Test
    void 승차_불가_정류장은_접근_차량을_비운다() {
        // when
        givenRoundTripBoard(List.of(prediction(2, "IGNORED", 1, 1, 0.2, 10.0)));

        // then
        assertThat(service.getBoard(ROUTE_ID).board().stops().get(1).approachingVehicles())
            .isEmpty();
    }

    @Test
    void 예보_행이_없으면_ACTIVE_모델을_사용한다() {
        // when
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(roundTripSnapshot()));
        given(repository.findStops(1L)).willReturn(roundTripStops());
        given(repository.findPredictions(100L)).willReturn(List.of());

        // then
        assertThat(service.getBoard(ROUTE_ID).board().model()).isEqualTo(ACTIVE_MODEL);
    }

    @Test
    void 하나의_관측_묶음에_여러_모델의_예보가_섞이면_서비스_불가다() {
        // when
        ForecastModel anotherModel = new ForecastModel(
            8L,
            "model-another",
            OffsetDateTime.parse("2026-08-24T23:59:59+09:00")
        );
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(roundTripSnapshot()));
        given(repository.findStops(1L)).willReturn(roundTripStops());
        given(repository.findPredictions(100L)).willReturn(List.of(
            prediction(3, "A", 1, 1, 0.2, 10.0),
            prediction(3, "B", 2, 2, 0.3, 11.0, anotherModel)
        ));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> service.getBoard(ROUTE_ID));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void ACTIVE_모델이_없으면_모델_범위_밖_오류다() {
        // when
        BoardSnapshot snapshot = new BoardSnapshot(
            1L,
            route(),
            2,
            schedule(),
            Optional.of(observation()),
            Optional.empty()
        );
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(snapshot));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> service.getBoard(ROUTE_ID));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(ModelOutOfScopeException.class);
    }

    @Test
    void 발행된_예보도_정상_관측도_없으면_최근_관측_없음_오류다() {
        // when
        BoardSnapshot snapshot = new BoardSnapshot(
            1L,
            route(),
            2,
            schedule(),
            Optional.empty(),
            Optional.of(ACTIVE_MODEL)
        );
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(snapshot));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> service.getBoard(ROUTE_ID));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(NoRecentObservationException.class);
    }

    @Test
    void 단방향은_하나의_방향과_그_방향의_시간표만_사용한다() {
        // given
        BoardSnapshot snapshot = new BoardSnapshot(
            2L,
            route(),
            null,
            new DepartureSchedule("05:00", "23:00", null, null, RouteHeadways.none()),
            Optional.of(observation()),
            Optional.of(ACTIVE_MODEL)
        );
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(snapshot));
        given(repository.findStops(2L)).willReturn(List.of(
            stop(1, "기점", BoardDirection.UP, true),
            stop(2, "종점", BoardDirection.UP, true)
        ));
        given(repository.findPredictions(100L)).willReturn(List.of());

        // when
        Board board = service.getBoard(ROUTE_ID).board();

        // then
        assertThat(board.route().turnSequence()).isNull();
        assertThat(board.route().directions()).singleElement().satisfies(direction -> {
            assertThat(direction.id()).isEqualTo(BoardDirection.UP);
            assertThat(direction.originStopName()).isEqualTo("기점");
            assertThat(direction.terminalStopName()).isEqualTo("종점");
            assertThat(direction.firstDepartureTime()).isEqualTo("05:00");
            assertThat(direction.lastDepartureTime()).isEqualTo("23:00");
        });
    }

    @Test
    void 회차점_이하에_DOWN_정류장이_있으면_서비스_불가다() {
        // when
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(roundTripSnapshot()));
        given(repository.findStops(1L)).willReturn(List.of(
            stop(1, "기점", BoardDirection.UP, true),
            stop(2, "회차점", BoardDirection.DOWN, true),
            stop(3, "종점", BoardDirection.DOWN, true)
        ));
        given(repository.findPredictions(100L)).willReturn(List.of());

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> service.getBoard(ROUTE_ID));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void 예보가_없는_차량도_거리순_최대_3대에_포함한다() {
        // given
        givenRoundTripBoard(List.of(prediction(3, "FAR", 4, 2, 0.2, 10.0)));
        given(repository.findObservedVehicles(100L, 1L)).willReturn(List.of(
            new BoardVehicleObservation("FAR", 4, 1, 1),
            new BoardVehicleObservation("C", 3, 2, 2),
            new BoardVehicleObservation("B", 2, 2, 2),
            new BoardVehicleObservation("A", 1, 2, 2)));

        // when
        List<ApproachingVehicle> vehicles = service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles();

        // then
        assertThat(vehicles).extracting(ApproachingVehicle::vehicleId).containsExactly("A", "B", "C");
        assertThat(vehicles).allSatisfy(vehicle ->
            assertThat(vehicle.forecast()).isInstanceOf(Unavailable.class));
    }

    @Test
    void 모든_예보가_없어도_관측된_접근_차량을_유지한다() {
        // when
        givenRoundTripBoard(List.of());
        given(repository.findObservedVehicles(100L, 1L)).willReturn(List.of(
            new BoardVehicleObservation("A", 1, 2, 2)));

        // then
        assertThat(service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles())
            .containsExactly(new ApproachingVehicle("A", 1, new Unavailable()));
    }

    @Test
    void 차량_ID가_없거나_같아도_원본_행_번호로_예보를_연결한다() {
        // when
        givenRoundTripBoard(List.of(prediction(3, null, 2, 1, 1.0, 0.0)));
        given(repository.findObservedVehicles(100L, 1L)).willReturn(List.of(
            new BoardVehicleObservation(null, 1, 2, 2), new BoardVehicleObservation(null, 2, 2, 2)));

        // then
        assertThat(service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles())
            .containsExactly(
                new ApproachingVehicle(null, 1, new Unavailable()),
                new ApproachingVehicle(null, 1, new Available(0.0, 0.0)));
    }

    @Test
    void 통과_정류장_기준_앞쪽_1부터_12정류장까지만_접근_차량을_표시한다() {
        // when
        givenRoundTripBoard(List.of());
        given(repository.findStops(1L)).willReturn(List.of(
            stop(1, "기점", BoardDirection.UP, true),
            stop(2, "회차점", BoardDirection.UP, false),
            stop(13, "종점", BoardDirection.DOWN, true)));
        given(repository.findObservedVehicles(100L, 1L)).willReturn(List.of(
            new BoardVehicleObservation("PAST", 1, 14, 14),
            new BoardVehicleObservation("HERE", 2, 13, 13),
            new BoardVehicleObservation("NEAR", 3, 12, 12),
            new BoardVehicleObservation("BOUNDARY", 4, 1, 1),
            new BoardVehicleObservation("TOO_FAR", 5, 0, 0)));

        // then
        assertThat(service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles())
            .extracting(ApproachingVehicle::vehicleId).containsExactly("NEAR", "BOUNDARY");
    }

    @Test
    void 대상_정류장에_서_있는_차량은_남은_정류장_0으로_노출하고_예보를_연결한다() {
        // given
        givenRoundTripBoard(List.of(prediction(3, "A", 1, 1, 0.25, 10.0)));
        given(repository.findObservedVehicles(100L, 1L)).willReturn(List.of(
            new BoardVehicleObservation("A", 1, 2, 3)));

        // when
        List<ApproachingVehicle> vehicles = service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles();

        // then
        assertThat(vehicles).containsExactly(new ApproachingVehicle("A", 0, new Available(0.75, 10.0)));
    }

    @Test
    void 앞_정류장에_서_있는_차량은_버스가_있는_정류장부터_세고_이동_중이거나_출발한_차량은_모델_거리와_같다() {
        // given
        givenVehiclesApproaching(20, List.of(
            new BoardVehicleObservation("ARRIVING", 1, 16, 17),
            new BoardVehicleObservation("IN_TRANSIT", 2, 15, 15),
            new BoardVehicleObservation("DEPARTED", 3, 14, 14)));

        // when
        List<ApproachingVehicle> vehicles = service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles();

        // then
        assertThat(vehicles).extracting(ApproachingVehicle::vehicleId, ApproachingVehicle::remainingStops)
            .containsExactly(tuple("ARRIVING", 3), tuple("IN_TRANSIT", 5), tuple("DEPARTED", 6));
    }

    @Test
    void 모델_거리가_12를_넘는_도착_차량은_남은_정류장이_12여도_노출하지_않고_모델_거리_12까지는_노출한다() {
        // given
        givenVehiclesApproaching(20, List.of(
            new BoardVehicleObservation("ARRIVING", 1, 7, 8),
            new BoardVehicleObservation("DEPARTED", 2, 8, 8),
            new BoardVehicleObservation("ARRIVING_EDGE", 3, 8, 9)));

        // when
        List<ApproachingVehicle> vehicles = service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles();

        // then
        assertThat(vehicles).extracting(ApproachingVehicle::vehicleId, ApproachingVehicle::remainingStops)
            .containsExactly(tuple("ARRIVING_EDGE", 11), tuple("DEPARTED", 12));
    }

    @Test
    void 남은_정류장이_가까운_차량부터_3대를_남긴다() {
        // given
        givenVehiclesApproaching(20, List.of(
            new BoardVehicleObservation("A", 1, 19, 19),
            new BoardVehicleObservation("B", 2, 17, 17),
            new BoardVehicleObservation("C", 3, 17, 17),
            new BoardVehicleObservation("D", 4, 17, 18)));

        // when
        List<ApproachingVehicle> vehicles = service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles();

        // then
        assertThat(vehicles).extracting(ApproachingVehicle::vehicleId, ApproachingVehicle::remainingStops)
            .containsExactly(tuple("A", 1), tuple("D", 2), tuple("B", 3));
    }

    @Test
    void 남은_정류장이_같으면_모델_거리가_짧은_차량이_앞선다() {
        // given
        givenVehiclesApproaching(20, List.of(
            new BoardVehicleObservation("A", 1, 16, 17),
            new BoardVehicleObservation("B", 2, 17, 17)));

        // when
        List<ApproachingVehicle> vehicles = service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles();

        // then
        assertThat(vehicles).extracting(ApproachingVehicle::vehicleId).containsExactly("B", "A");
    }

    @Test
    void 두_거리가_같으면_차량_ID가_있는_차량이_ID_없는_차량보다_앞선다() {
        // given
        givenVehiclesApproaching(20, List.of(
            new BoardVehicleObservation(null, 1, 17, 17),
            new BoardVehicleObservation("Z", 2, 17, 17)));

        // when
        List<ApproachingVehicle> vehicles = service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles();

        // then
        assertThat(vehicles).extracting(ApproachingVehicle::vehicleId).containsExactly("Z", null);
    }

    @Test
    void 버스가_있는_정류장과_지난_정류장의_정합이_깨진_관측은_노출하지_않는다() {
        // given
        givenVehiclesApproaching(20, List.of(
            new BoardVehicleObservation("PAST_TARGET", 1, 19, 21),
            new BoardVehicleObservation("BEHIND_PASSED", 2, 15, 13),
            new BoardVehicleObservation("AHEAD_OF_PASSED", 3, 15, 17),
            new BoardVehicleObservation("CONSISTENT", 4, 15, 16)));

        // when
        List<ApproachingVehicle> vehicles = service.getBoard(ROUTE_ID).board().stops().get(2).approachingVehicles();

        // then
        assertThat(vehicles).extracting(ApproachingVehicle::vehicleId, ApproachingVehicle::remainingStops)
            .containsExactly(tuple("CONSISTENT", 4));
    }

    @Test
    void 발행되지_않은_관측이면_예보를_조회하지_않고_모든_차량을_예보_없음으로_둔_준비_중_보드를_만든다() {
        // given
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(snapshotObserving(
            new SnapshotObservation(100L, OBSERVED_AT, 2, false))));
        given(repository.findStops(1L)).willReturn(roundTripStops());
        given(repository.findObservedVehicles(100L, 1L)).willReturn(List.of(
            new BoardVehicleObservation("A", 1, 2, 2),
            new BoardVehicleObservation("B", 2, 1, 1)));

        // when
        BoardOverview actual = service.getBoard(ROUTE_ID);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(actual.board().route().status()).isEqualTo(RouteStatus.PREPARING);
            softly.assertThat(actual.board().model()).isEqualTo(ACTIVE_MODEL);
            softly.assertThat(actual.board().observedAt()).isEqualTo(OBSERVED_AT);
            softly.assertThat(actual.board().vehiclesInService()).isEqualTo(2);
            softly.assertThat(actual.cacheMaxAge()).isEqualTo(Duration.ofSeconds(15));
        }
        assertThat(actual.board().stops().get(2).approachingVehicles())
            .extracting(ApproachingVehicle::forecast)
            .containsExactly(new Unavailable(), new Unavailable());
        verify(repository, never()).findPredictions(anyLong());
    }

    @Test
    void 발행된_관측이면_예보가_준비된_보드를_만든다() {
        // given
        givenRoundTripBoard(List.of(prediction(3, "A", 1, 1, 0.2, 10.0)));

        // when
        Board actual = service.getBoard(ROUTE_ID).board();

        // then
        assertThat(actual.route().status()).isEqualTo(RouteStatus.FORECAST_READY);
    }

    @Test
    void 발행되지_않은_관측도_5분을_넘으면_최근_관측_없음_오류다() {
        // given
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(snapshotObserving(
            new SnapshotObservation(100L, OBSERVED_AT.minusMinutes(1), 2, false))));

        // when
        Throwable caughtFailure = catchThrowable(() -> service.getBoard(ROUTE_ID));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(NoRecentObservationException.class);
    }

    private BoardSnapshot snapshotObserving(
        SnapshotObservation observation
    ) {
        return new BoardSnapshot(
            1L,
            route(),
            2,
            schedule(),
            Optional.of(observation),
            Optional.of(ACTIVE_MODEL)
        );
    }

    private BoardSnapshot roundTripSnapshot() {
        return new BoardSnapshot(
            1L,
            route(),
            2,
            schedule(),
            Optional.of(observation()),
            Optional.of(ACTIVE_MODEL)
        );
    }

    private void givenRoundTripBoard(List<StoredPrediction> predictions) {
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(roundTripSnapshot()));
        given(repository.findStops(1L)).willReturn(roundTripStops());
        given(repository.findPredictions(100L)).willReturn(predictions);
        given(repository.findObservedVehicles(100L, 1L)).willReturn(predictions.stream()
                .map(p -> new BoardVehicleObservation(p.vehicleId(), p.sourceRowNumber(),
                    p.targetStopOrder() - p.stopsToTarget(), p.targetStopOrder() - p.stopsToTarget()))
                .distinct()
                .toList());
    }

    private void givenVehiclesApproaching(final int targetSequence, List<BoardVehicleObservation> vehicles) {
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(roundTripSnapshot()));
        given(repository.findStops(1L)).willReturn(List.of(
            stop(1, "기점", BoardDirection.UP, true),
            stop(2, "회차점", BoardDirection.UP, false),
            stop(targetSequence, "종점", BoardDirection.DOWN, true)));
        given(repository.findPredictions(100L)).willReturn(List.of());
        given(repository.findObservedVehicles(100L, 1L)).willReturn(vehicles);
    }

    private Route route() {
        return new Route("204000057", "3330", "기점", "회차점");
    }

    private DepartureSchedule schedule() {
        return new DepartureSchedule("04:50", "23:30", "05:00", "23:30", RouteHeadways.none());
    }

    private SnapshotObservation observation() {
        return new SnapshotObservation(100L, OBSERVED_AT, 4, true);
    }

    private List<BoardStop> roundTripStops() {
        return List.of(
            stop(1, "기점", BoardDirection.UP, true),
            stop(2, "회차점", BoardDirection.UP, false),
            stop(3, "종점", BoardDirection.DOWN, true)
        );
    }

    private BoardStop stop(
        final int sequence,
        String name,
        BoardDirection direction,
        final boolean boardingAllowed
    ) {
        return new BoardStop(sequence, "STOP-" + sequence, name, direction, boardingAllowed);
    }

    private StoredPrediction prediction(
        final int targetStopOrder,
        String vehicleId,
        final int sourceRowNumber,
        final int stopsToTarget,
        final double seatFullChance,
        Double expectedSeats
    ) {
        return prediction(
            targetStopOrder,
            vehicleId,
            sourceRowNumber,
            stopsToTarget,
            seatFullChance,
            expectedSeats,
            SNAPSHOT_MODEL
        );
    }

    private StoredPrediction prediction(
        final int targetStopOrder,
        String vehicleId,
        final int sourceRowNumber,
        final int stopsToTarget,
        final double seatFullChance,
        Double expectedSeats,
        ForecastModel model
    ) {
        return new StoredPrediction(
            targetStopOrder,
            vehicleId,
            sourceRowNumber,
            stopsToTarget,
            seatFullChance,
            expectedSeats,
            model
        );
    }
}
