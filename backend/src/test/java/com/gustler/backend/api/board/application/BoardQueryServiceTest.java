package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.http.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.domain.Route;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        final Clock clock = Clock.fixed(
            Instant.parse("2026-08-26T23:04:59Z"),
            ZoneOffset.UTC
        );
        service = new BoardQueryService(
            repository,
            new BoardFreshnessPolicy(clock),
            new BoardCachePolicy()
        );
    }

    @Test
    void 저장된_예보를_검증하고_정렬한_뒤_정류장별_최대_3대를_조립한다() {
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(roundTripSnapshot()));
        given(repository.findStops(1L)).willReturn(roundTripStops());
        given(repository.findPredictions(100L)).willReturn(List.of(
            prediction(3, "C", 5, 3, 0.4, 12.0),
            prediction(3, null, 4, 1, 0.2, null),
            prediction(3, "A", 3, 2, 0.1, 20.0),
            prediction(3, "B", 2, 1, 0.3, 15.0),
            prediction(3, "0", 1, 1, 0.5, Double.POSITIVE_INFINITY),
            prediction(2, "IGNORED", 6, 1, 0.2, 10.0)
        ));

        final BoardOverview overview = service.getBoard(ROUTE_ID);

        final Board board = overview.board();
        assertThat(board.model()).isEqualTo(SNAPSHOT_MODEL);
        assertThat(board.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(board.vehiclesInService()).isEqualTo(4);
        assertThat(overview.cacheMaxAge()).isEqualTo(Duration.ofSeconds(15));
        assertThat(board.route().directions())
            .extracting(direction -> direction.name())
            .containsExactly("회차점 방면", "종점 방면");

        final StopState boardingStop = board.stops().get(2);
        assertThat(boardingStop.approachingVehicles())
            .extracting(ApproachingVehicle::vehicleId)
            .containsExactly("B", null, "A");
        assertThat(boardingStop.approachingVehicles().getFirst().seatAvailableProbability())
            .isEqualTo(0.7d);
        assertThat(boardingStop.approachingVehicles().get(1).expectedSeats()).isNull();
        assertThat(board.stops().get(1).approachingVehicles()).isEmpty();
    }

    @Test
    void 예보_행이_없으면_ACTIVE_모델을_사용한다() {
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(roundTripSnapshot()));
        given(repository.findStops(1L)).willReturn(roundTripStops());
        given(repository.findPredictions(100L)).willReturn(List.of());

        assertThat(service.getBoard(ROUTE_ID).board().model()).isEqualTo(ACTIVE_MODEL);
    }

    @Test
    void ACTIVE_모델이_없으면_모델_범위_밖_오류다() {
        final BoardSnapshot snapshot = new BoardSnapshot(
            1L,
            route(),
            2,
            schedule(),
            Optional.of(observation()),
            Optional.empty()
        );
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.getBoard(ROUTE_ID))
            .isInstanceOf(ModelOutOfScopeException.class);
    }

    @Test
    void 완료된_poll이_없으면_최근_관측_없음_오류다() {
        final BoardSnapshot snapshot = new BoardSnapshot(
            1L,
            route(),
            2,
            schedule(),
            Optional.empty(),
            Optional.of(ACTIVE_MODEL)
        );
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.getBoard(ROUTE_ID))
            .isInstanceOf(NoRecentObservationException.class);
    }

    @Test
    void 단방향은_하나의_방향과_그_방향의_시간표만_사용한다() {
        final BoardSnapshot snapshot = new BoardSnapshot(
            2L,
            route(),
            null,
            new DepartureSchedule("05:00", "23:00", null, null),
            Optional.of(observation()),
            Optional.of(ACTIVE_MODEL)
        );
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(snapshot));
        given(repository.findStops(2L)).willReturn(List.of(
            stop(1, "기점", BoardDirection.UP, true),
            stop(2, "종점", BoardDirection.UP, true)
        ));
        given(repository.findPredictions(100L)).willReturn(List.of());

        final Board board = service.getBoard(ROUTE_ID).board();

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
    void 방향_골격과_시간표가_계약을_만들지_못하면_서비스_불가다() {
        given(repository.findSnapshot(ROUTE_ID)).willReturn(Optional.of(roundTripSnapshot()));
        given(repository.findStops(1L)).willReturn(List.of(
            stop(1, "기점", BoardDirection.UP, true),
            stop(2, "회차점", BoardDirection.DOWN, true),
            stop(3, "종점", BoardDirection.DOWN, true)
        ));
        given(repository.findPredictions(100L)).willReturn(List.of());

        assertThatThrownBy(() -> service.getBoard(ROUTE_ID))
            .isInstanceOf(ServiceUnavailableException.class);
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

    private Route route() {
        return new Route("204000057", "3330", "기점", "회차점");
    }

    private DepartureSchedule schedule() {
        return new DepartureSchedule("04:50", "23:30", "05:00", "23:30");
    }

    private SnapshotObservation observation() {
        return new SnapshotObservation(100L, OBSERVED_AT, 4);
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
        final String name,
        final BoardDirection direction,
        final boolean boardingAllowed
    ) {
        return new BoardStop(sequence, "STOP-" + sequence, name, direction, boardingAllowed);
    }

    private StoredPrediction prediction(
        final int targetStopOrder,
        final String vehicleId,
        final int sourceRowNumber,
        final int horizonStops,
        final double pFull,
        final Double expectedSeats
    ) {
        return new StoredPrediction(
            targetStopOrder,
            vehicleId,
            sourceRowNumber,
            horizonStops,
            pFull,
            expectedSeats,
            SNAPSHOT_MODEL
        );
    }
}
