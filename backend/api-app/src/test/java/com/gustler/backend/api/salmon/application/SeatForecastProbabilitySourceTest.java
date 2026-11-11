package com.gustler.backend.api.salmon.application;

import static com.gustler.backend.api.salmon.domain.ActionFixture.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
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
import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.route.domain.RouteStatus;
import com.gustler.backend.api.salmon.application.ArrivalStopSource.CoverageRule;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.Cause;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.EntryLookup;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.Found;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.NotFound;
import com.gustler.backend.api.salmon.application.WalkOption.Relation;
import com.gustler.backend.api.salmon.domain.ActionConfig;
import com.gustler.backend.api.salmon.domain.ActionDecider;
import com.gustler.backend.api.salmon.domain.ActionDecider.Decision;
import com.gustler.backend.api.salmon.domain.ActionJudge.Baseline;
import com.gustler.backend.api.salmon.domain.ActionJudge.CandidateSource;
import com.gustler.backend.api.salmon.domain.Fields.MissingReason;
import com.gustler.backend.api.salmon.domain.Fields.Observed;
import com.gustler.backend.api.salmon.domain.Probabilities.ProbabilityInput;
import com.gustler.backend.api.salmon.domain.Probabilities.ProbabilitySupply;
import com.gustler.backend.api.salmon.domain.Probabilities.ValueOrigin;
import com.gustler.backend.api.salmon.domain.Probabilities.ValueTag;
import com.gustler.backend.api.salmon.domain.StopLists.Coverage;
import com.gustler.backend.api.salmon.domain.StopLists.Queried;
import com.gustler.backend.api.salmon.domain.StopLists.StopEntry;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import com.gustler.backend.api.salmon.domain.StopLists.Unavailable;
import com.gustler.backend.api.salmon.domain.Timing.Offset;
import com.gustler.backend.api.salmon.domain.Verdicts.Assumption;
import com.gustler.backend.api.salmon.domain.Verdicts.Attempt;
import com.gustler.backend.api.salmon.domain.Verdicts.CandidateRecord;
import com.gustler.backend.api.salmon.domain.Verdicts.Evaluable;
import com.gustler.backend.api.salmon.domain.Verdicts.ReasonCode;
import com.gustler.backend.api.salmon.domain.Verdicts.Verdict;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class SeatForecastProbabilitySourceTest {
    private static final Instant RECEIVED = Instant.parse("2026-11-08T09:00:00Z");
    private static final Instant NOW = RECEIVED.plusSeconds(30);
    private static final String PUBLIC_ROUTE = "204000057";
    private static final String SOURCE_ROUTE = "224000057";
    private static final String STATION = "228000001";
    private static final String UPSTREAM_STATION = "228000002";
    private static final long ROUTE_VERSION = 7;
    private static final double TOLERANCE = 1e-9;

    private final BoardQueryService boards = mock(BoardQueryService.class);

    @Test
    void 좌석_예보가_있는_도착_항목마다_1에서_출발_만석_확률을_뺀_값으로_주변확률_입력을_만든다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3), departureFull("bus-2", 0.6))));
        StopList list = listOf(entry("first", 4, "bus-1"), entry("second", 11, "bus-2"));

        ProbabilitySupply supply = source(visitsOf(visit(STATION, 5)))
            .probabilitiesFor(PUBLIC_ROUTE, STATION, 5, list, NOW);

        assertThat(supply.inputs()).extracting(ProbabilityInput::attempt).containsExactly("first", "second");
        assertThat(supply.inputs()).allSatisfy(input -> {
            assertThat(input.tag()).isEqualTo(ValueTag.MARG);
            assertThat(input.condition()).isEmpty();
            assertThat(input.origin()).isEqualTo(ValueOrigin.SEAT_FORECAST);
        });
        assertThat(supply.inputs().get(0).value()).isCloseTo(0.7, within(TOLERANCE));
        assertThat(supply.inputs().get(1).value()).isCloseTo(0.4, within(TOLERANCE));
    }

    @Test
    void 차량_ID가_빈_항목은_값_없음으로_둔다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));
        StopList list = listOf(new StopEntry("unknown", new Observed<>(Offset.ofMinutes(4))),
            entry("known", 11, "bus-1"));

        ProbabilitySupply supply = source(visitsOf(visit(STATION, 5)))
            .probabilitiesFor(PUBLIC_ROUTE, STATION, 5, list, NOW);

        assertThat(supply.inputs()).extracting(ProbabilityInput::attempt).containsExactly("known");
    }

    @Test
    void 보드에_없는_차량과_예보를_제공하지_못한_차량의_항목은_값_없음으로_둔다() {
        boardIs(boardOf(stopOf(5, STATION,
            new ApproachingVehicle("bus-2", 3, new VehicleForecast.Unavailable()))));
        StopList list = listOf(entry("absent", 4, "bus-1"), entry("unforecast", 11, "bus-2"));

        ProbabilitySupply supply = source(visitsOf(visit(STATION, 5)))
            .probabilitiesFor(PUBLIC_ROUTE, STATION, 5, list, NOW);

        assertThat(supply.inputs()).isEmpty();
    }

    @Test
    void 확률_목표가_FIRST_POST_STOP_ZERO인_예보에서도_1에서_만석_확률을_뺀_값을_만든다() {
        boardIs(boardOf(stopOf(5, STATION, new ApproachingVehicle("bus-1", 2,
            new VehicleForecast.Available(0.7, 12.0, "FIRST_POST_STOP_OBSERVATION", "FIRST_POST_STOP_ZERO")))));

        ProbabilitySupply supply = source(visitsOf(visit(STATION, 5)))
            .probabilitiesFor(PUBLIC_ROUTE, STATION, 5, listOf(entry("first", 4, "bus-1")), NOW);

        assertThat(supply.inputs()).singleElement().satisfies(input -> {
            assertThat(input.attempt()).isEqualTo("first");
            assertThat(input.tag()).isEqualTo(ValueTag.MARG);
            assertThat(input.condition()).isEmpty();
            assertThat(input.value()).isCloseTo(0.7, within(TOLERANCE));
            assertThat(input.origin()).isEqualTo(ValueOrigin.SEAT_FORECAST);
        });
    }

    @Test
    void 그_밖의_확률_목표와_확률_목표를_모르는_예보는_쓰지_않는다() {
        SeatForecastProbabilitySource source = source(visitsOf(visit(STATION, 5)));

        for (String target : Arrays.asList("ARRIVAL_ZERO", null)) {
            boardIs(boardOf(stopOf(5, STATION, new ApproachingVehicle("bus-1", 2,
                new VehicleForecast.Available(0.7, 12.0, "FIRST_POST_STOP_OBSERVATION", target, 0.3)))));

            assertThat(source.probabilitiesFor(PUBLIC_ROUTE, STATION, 5, listOf(entry("first", 4, "bus-1")), NOW)
                .inputs()).as("%s", target).isEmpty();
        }
    }

    @Test
    void 같은_차량이라도_다른_정류장_방문을_목표로_한_예보는_쓰지_않는다() {
        boardIs(boardOf(stopOf(5, STATION), stopOf(6, "228000003", departureFull("bus-1", 0.3))));

        ProbabilitySupply supply = source(visitsOf(visit(STATION, 5)))
            .probabilitiesFor(PUBLIC_ROUTE, STATION, 5, listOf(entry("first", 4, "bus-1")), NOW);

        assertThat(supply.inputs()).isEmpty();
    }

    @Test
    void 보드의_같은_정류장에_같은_차량_ID가_둘이면_어느_예보인지_정하지_않고_값_없음으로_둔다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3), departureFull("bus-1", 0.9))));

        ProbabilitySupply supply = source(visitsOf(visit(STATION, 5)))
            .probabilitiesFor(PUBLIC_ROUTE, STATION, 5, listOf(entry("first", 4, "bus-1")), NOW);

        assertThat(supply.inputs()).isEmpty();
    }

    @Test
    void 예보를_낸_관측의_만료_시각이_판정_시각보다_이르면_값_없음이고_같으면_값을_낸다() {
        StopList list = listOf(entry("first", 4, "bus-1"));
        SeatForecastProbabilitySource source = source(visitsOf(visit(STATION, 5)));

        boardIs(boardOf(NOW.minusMillis(1), stopOf(5, STATION, departureFull("bus-1", 0.3))));
        assertThat(source.probabilitiesFor(PUBLIC_ROUTE, STATION, 5, list, NOW).inputs()).isEmpty();

        boardIs(boardOf(NOW, stopOf(5, STATION, departureFull("bus-1", 0.3))));
        assertThat(source.probabilitiesFor(PUBLIC_ROUTE, STATION, 5, list, NOW).inputs()).hasSize(1);
    }

    @Test
    void 보드를_읽지_못하면_값_없음이다() {
        StopList list = listOf(entry("first", 4, "bus-1"));
        SeatForecastProbabilitySource source = source(visitsOf(visit(STATION, 5)));

        for (RuntimeException failure : List.of(new NoRecentObservationException(), new ModelOutOfScopeException(),
            new RouteNotFoundException(), new ServiceUnavailableException(),
            new DataAccessResourceFailureException("connection refused"))) {
            willThrow(failure).given(boards).readBoard(new RouteId(PUBLIC_ROUTE));

            assertThat(source.probabilitiesFor(PUBLIC_ROUTE, STATION, 5, list, NOW).inputs())
                .as("%s", failure.getClass().getSimpleName()).isEmpty();
        }
    }

    @Test
    void 보드와_도착_방문의_노선_판본이_다르면_값_없음이다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));
        ArrivalVisit otherVersion = new ArrivalVisit(PUBLIC_ROUTE, SOURCE_ROUTE, ROUTE_VERSION + 1, 5, "UP",
            RECEIVED.minusSeconds(3600));

        ProbabilitySupply supply = source(visitsOf(otherVersion))
            .probabilitiesFor(PUBLIC_ROUTE, STATION, 5, listOf(entry("first", 4, "bus-1")), NOW);

        assertThat(supply.inputs()).isEmpty();
    }

    @Test
    void 정류장_방문을_확인하지_못하면_값_없음이다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));

        ProbabilitySupply supply = source(visitsOf(visit(STATION, 12)))
            .probabilitiesFor(PUBLIC_ROUTE, STATION, 5, listOf(entry("first", 4, "bus-1")), NOW);

        assertThat(supply.inputs()).isEmpty();
    }

    @Test
    void 정류장_방문_조회가_실패하면_값_없음이다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));
        ArrivalReadRepository failing = new ArrivalReadRepository() {
            @Override
            public List<ArrivalVisit> visits(String stationId) {
                throw new DataAccessResourceFailureException("connection refused");
            }

            @Override
            public Optional<ArrivalSnapshot> latest(String stationId) {
                return Optional.empty();
            }
        };

        ProbabilitySupply supply = new SeatForecastProbabilitySource(boards, failing)
            .probabilitiesFor(PUBLIC_ROUTE, STATION, 5, listOf(entry("first", 4, "bus-1")), NOW);

        assertThat(supply.inputs()).isEmpty();
    }

    @Test
    void 차량_ID가_있는_항목이_없으면_보드를_읽지_않는다() {
        SeatForecastProbabilitySource source = source(visitsOf(visit(STATION, 5)));

        assertThat(source.probabilitiesFor(PUBLIC_ROUTE, STATION, 5, new Unavailable(MissingReason.STALE), NOW)
            .inputs()).isEmpty();
        assertThat(source.probabilitiesFor(PUBLIC_ROUTE, STATION, 5,
            listOf(new StopEntry("unknown", new Observed<>(Offset.ofMinutes(4)))), NOW).inputs()).isEmpty();
        then(boards).should(never()).readBoard(any());
    }

    @Test
    void 항목마다_값과_그_값을_낸_예보의_확률_목표를_돌려준다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3), new ApproachingVehicle("bus-2", 4,
            new VehicleForecast.Available(0.4, 12.0, "FIRST_POST_STOP_OBSERVATION", "FIRST_POST_STOP_ZERO")))));

        List<EntryLookup> lookups = lookupsFor(listOf(entry("first", 4, "bus-1"), entry("second", 11, "bus-2")));

        assertThat(lookups).extracting(EntryLookup::entryId).containsExactly("first", "second");
        assertThat(lookups.get(0).lookup()).isInstanceOfSatisfying(Found.class, found -> {
            assertThat(found.value()).isCloseTo(0.7, within(TOLERANCE));
            assertThat(found.probabilityTarget()).isEqualTo("DEPARTURE_ZERO");
        });
        assertThat(lookups.get(1).lookup()).isInstanceOfSatisfying(Found.class, found -> {
            assertThat(found.value()).isCloseTo(0.4, within(TOLERANCE));
            assertThat(found.probabilityTarget()).isEqualTo("FIRST_POST_STOP_ZERO");
        });
    }

    @Test
    void 차량_ID가_있는_항목이_없으면_모든_항목의_원인이_목록에_차량_ID_없음이고_보드를_읽지_않는다() {
        List<EntryLookup> lookups = lookupsFor(listOf(new StopEntry("first", new Observed<>(Offset.ofMinutes(4))),
            new StopEntry("second", new Observed<>(Offset.ofMinutes(11)))));

        assertThat(lookups).extracting(EntryLookup::entryId).containsExactly("first", "second");
        assertThat(lookups).extracting(EntryLookup::lookup).containsOnly(new NotFound(Cause.LIST_WITHOUT_VEHICLE_ID));
        then(boards).should(never()).readBoard(any());
    }

    @Test
    void 보드_읽기가_거부되면_모든_항목의_원인이_보드_읽기_실패이고_오류_코드를_함께_돌려준다() {
        StopList list = listOf(entry("first", 4, "bus-1"),
            new StopEntry("second", new Observed<>(Offset.ofMinutes(11))));

        Map<ErrorCode, ApiException> failures = Map.of(
            ErrorCode.ROUTE_NOT_FOUND, new RouteNotFoundException(),
            ErrorCode.MODEL_OUT_OF_SCOPE, new ModelOutOfScopeException(),
            ErrorCode.NO_RECENT_OBSERVATION, new NoRecentObservationException(),
            ErrorCode.SERVICE_UNAVAILABLE, new ServiceUnavailableException());
        failures.forEach((code, failure) -> {
            willThrow(failure).given(boards).readBoard(new RouteId(PUBLIC_ROUTE));

            assertThat(lookupsFor(list)).as("%s", code).extracting(EntryLookup::lookup)
                .containsExactly(new NotFound(Cause.BOARD_READ_FAILED, Optional.of(code)),
                    new NotFound(Cause.BOARD_READ_FAILED, Optional.of(code)));
        });
    }

    @Test
    void 보드나_정류장_방문의_DB_조회가_실패하면_모든_항목의_원인이_조회_실패다() {
        StopList list = listOf(entry("first", 4, "bus-1"), entry("second", 11, "bus-2"));
        ArrivalReadRepository failingVisits = new ArrivalReadRepository() {
            @Override
            public List<ArrivalVisit> visits(String stationId) {
                throw new DataAccessResourceFailureException("connection refused");
            }

            @Override
            public Optional<ArrivalSnapshot> latest(String stationId) {
                return Optional.empty();
            }
        };

        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));
        assertThat(new SeatForecastProbabilitySource(boards, failingVisits)
            .lookupsFor(PUBLIC_ROUTE, STATION, 5, list, NOW)).extracting(EntryLookup::lookup)
            .containsExactly(new NotFound(Cause.QUERY_FAILED), new NotFound(Cause.QUERY_FAILED));

        willThrow(new DataAccessResourceFailureException("connection refused"))
            .given(boards).readBoard(new RouteId(PUBLIC_ROUTE));
        assertThat(lookupsFor(list)).extracting(EntryLookup::lookup)
            .containsExactly(new NotFound(Cause.QUERY_FAILED), new NotFound(Cause.QUERY_FAILED));
    }

    @Test
    void 판정_시각이_보드_만료_시각을_지났으면_모든_항목의_원인이_보드_만료다() {
        boardIs(boardOf(NOW.minusMillis(1), stopOf(5, STATION, departureFull("bus-1", 0.3))));

        assertThat(lookupsFor(listOf(entry("first", 4, "bus-1"), entry("second", 11, "bus-2"))))
            .extracting(EntryLookup::lookup)
            .containsExactly(new NotFound(Cause.BOARD_STALE), new NotFound(Cause.BOARD_STALE));
    }

    @Test
    void 정류장_방문을_찾지_못했거나_노선_판본이_다르면_모든_항목의_원인이_방문_불일치다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));
        StopList list = listOf(entry("first", 4, "bus-1"), entry("second", 11, "bus-2"));
        ArrivalVisit otherVersion = new ArrivalVisit(PUBLIC_ROUTE, SOURCE_ROUTE, ROUTE_VERSION + 1, 5, "UP",
            RECEIVED.minusSeconds(3600));

        for (ArrivalReadRepository arrivals : List.of(visitsOf(visit(STATION, 12)), visitsOf(otherVersion))) {
            assertThat(source(arrivals).lookupsFor(PUBLIC_ROUTE, STATION, 5, list, NOW))
                .extracting(EntryLookup::lookup)
                .containsExactly(new NotFound(Cause.VISIT_NOT_MATCHED), new NotFound(Cause.VISIT_NOT_MATCHED));
        }
    }

    @Test
    void 차량_ID가_없는_항목의_원인은_항목에_차량_ID_없음이다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));

        List<EntryLookup> lookups = lookupsFor(listOf(new StopEntry("unknown", new Observed<>(Offset.ofMinutes(4))),
            entry("known", 11, "bus-1")));

        assertThat(lookups.get(0)).isEqualTo(new EntryLookup("unknown", new NotFound(Cause.ENTRY_WITHOUT_VEHICLE_ID)));
        assertThat(lookups.get(1).lookup()).isInstanceOf(Found.class);
    }

    @Test
    void 보드의_그_정류장_방문에_차량이_없는_항목의_원인은_보드에_차량_없음이다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-2", 0.3)), stopOf(6, "228000003",
            departureFull("bus-1", 0.3))));

        assertThat(lookupsFor(listOf(entry("first", 4, "bus-1")))).extracting(EntryLookup::lookup)
            .containsExactly(new NotFound(Cause.VEHICLE_NOT_ON_BOARD));
    }

    @Test
    void 보드의_그_정류장_방문에_같은_차량_ID가_둘인_항목의_원인은_차량_중복이다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3), departureFull("bus-1", 0.9))));

        assertThat(lookupsFor(listOf(entry("first", 4, "bus-1")))).extracting(EntryLookup::lookup)
            .containsExactly(new NotFound(Cause.VEHICLE_AMBIGUOUS));
    }

    @Test
    void 보드가_예보를_제공하지_못한_차량의_항목은_원인이_예보_제공_불가다() {
        boardIs(boardOf(stopOf(5, STATION, new ApproachingVehicle("bus-1", 3, new VehicleForecast.Unavailable()))));

        assertThat(lookupsFor(listOf(entry("first", 4, "bus-1")))).extracting(EntryLookup::lookup)
            .containsExactly(new NotFound(Cause.FORECAST_UNAVAILABLE));
    }

    @Test
    void 쓰지_않는_확률_목표의_예보만_있는_항목은_원인이_쓰지_않는_확률_목표다() {
        for (String target : Arrays.asList("ARRIVAL_ZERO", null)) {
            boardIs(boardOf(stopOf(5, STATION, new ApproachingVehicle("bus-1", 2,
                new VehicleForecast.Available(0.7, 12.0, "FIRST_POST_STOP_OBSERVATION", target, 0.3)))));

            assertThat(lookupsFor(listOf(entry("first", 4, "bus-1")))).as("%s", target)
                .extracting(EntryLookup::lookup)
                .containsExactly(new NotFound(Cause.PROBABILITY_TARGET_NOT_USED));
        }
    }

    @Test
    void 오류_코드는_보드_읽기_실패_원인에만_붙는다() {
        assertThatThrownBy(() -> new NotFound(Cause.BOARD_READ_FAILED))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotFound(Cause.BOARD_STALE, Optional.of(ErrorCode.SERVICE_UNAVAILABLE)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 좌석_예보_값으로_판정하면_결과에_근사_표지가_남고_시도가_둘_이상이면_독립_가정_표지도_남는다() {
        ArrivalReadRepository arrivals = arrivalsOf(
            Map.of(STATION, List.of(visit(STATION, 5)), UPSTREAM_STATION, List.of(visit(UPSTREAM_STATION, 4))),
            Map.of(STATION, snapshotOf(STATION, 5, prediction("bus-1", 1, 270), prediction("bus-2", 2, 690)),
                UPSTREAM_STATION, snapshotOf(UPSTREAM_STATION, 4, prediction("bus-2", 1, 570),
                    prediction("bus-3", 2, 1230))));
        boardIs(boardOf(
            stopOf(4, UPSTREAM_STATION, departureFull("bus-2", 0.2), departureFull("bus-3", 0.1)),
            stopOf(5, STATION, departureFull("bus-1", 0.7), departureFull("bus-2", 0.6))));
        ActionConfig config = config(15, 8);
        ArrivalStopSource stops = new ArrivalStopSource(arrivals, CoverageRule.NONE, config);
        SeatForecastProbabilitySource probabilities = new SeatForecastProbabilitySource(boards, arrivals);
        StopList current = stops.arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW);
        StopList upstream = stops.arrivalsAt(PUBLIC_ROUTE, UPSTREAM_STATION, 4, NOW);
        ProbabilitySupply currentSupply = probabilities.probabilitiesFor(PUBLIC_ROUTE, STATION, 5, current, NOW);
        ProbabilitySupply upstreamSupply =
            probabilities.probabilitiesFor(PUBLIC_ROUTE, UPSTREAM_STATION, 4, upstream, NOW);
        CandidateSource candidates = epoch -> CandidateAssembler.assemble(PUBLIC_ROUTE, NOW,
            List.of(new WalkOption(UPSTREAM_STATION, 4, Relation.BEFORE, 180)), stops,
            (routeId, option) -> Optional.of(0.0), upstreamSupply);

        Decision decision = ActionDecider.decide(new ActionDecider.Sources() {
            @Override
            public Baseline baseline(String epoch, Optional<StopList> override) {
                return new Baseline(epoch, override.orElse(current), currentSupply);
            }

            @Override
            public CandidateSource candidates() {
                return candidates;
            }
        }, config);

        Verdict verdict = decision.verdict();
        assertThat(currentSupply.inputs()).extracting(ProbabilityInput::value)
            .satisfiesExactly(value -> assertThat(value).isCloseTo(0.3, within(TOLERANCE)),
                value -> assertThat(value).isCloseTo(0.4, within(TOLERANCE)));
        assertThat(upstreamSupply.inputs()).extracting(ProbabilityInput::value)
            .satisfiesExactly(value -> assertThat(value).isCloseTo(0.8, within(TOLERANCE)),
                value -> assertThat(value).isCloseTo(0.9, within(TOLERANCE)));
        Evaluable stay = (Evaluable) verdict.stay().evaluation();
        assertThat(verdict.stay().attempts()).extracting(Attempt::arrival)
            .containsExactly(Offset.ofMinutes(4), Offset.ofMinutes(11));
        assertThat(stay.value()).isCloseTo(0.42, within(TOLERANCE));
        assertThat(stay.assumptions()).containsExactly(Assumption.INDEP, Assumption.SEAT_FORECAST_PROXY);
        CandidateRecord walk = verdict.candidates().get(0);
        Evaluable walked = (Evaluable) walk.outcome().evaluation();
        assertThat(walk.outcome().attempts()).extracting(Attempt::arrival).containsExactly(Offset.ofMinutes(9));
        assertThat(walked.value()).isCloseTo(0.2, within(TOLERANCE));
        assertThat(walked.assumptions()).containsExactly(Assumption.SEAT_FORECAST_PROXY);
        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        assertThat(verdict.bestCandidate()).contains("walk-" + UPSTREAM_STATION + "-4");
        assertThat(verdict.delta().orElseThrow()).isCloseTo(0.22, within(TOLERANCE));
    }

    private void boardIs(Board board) {
        given(boards.readBoard(new RouteId(PUBLIC_ROUTE)))
            .willReturn(new BoardReading(board, 1L, Duration.ofSeconds(15)));
    }

    private List<EntryLookup> lookupsFor(StopList list) {
        return source(visitsOf(visit(STATION, 5))).lookupsFor(PUBLIC_ROUTE, STATION, 5, list, NOW);
    }

    private SeatForecastProbabilitySource source(ArrivalReadRepository arrivals) {
        return new SeatForecastProbabilitySource(boards, arrivals);
    }

    private static Board boardOf(StopState... stops) {
        return boardOf(RECEIVED.plusSeconds(300), stops);
    }

    private static Board boardOf(Instant staleAt, StopState... stops) {
        BoardRoute route = new BoardRoute(PUBLIC_ROUTE, "9300", "기점", "종점", RouteStatus.FORECAST_READY, null,
            List.of(new DirectionInfo(BoardDirection.UP, "종점 방면", "기점", "종점", "05:00", "22:30")),
            Long.toString(ROUTE_VERSION), RouteHeadways.none());
        OffsetDateTime stale = staleAt.atOffset(ZoneOffset.UTC);
        return new Board(route, stale.minusMinutes(5), stale,
            new ForecastModel(1, "release", stale.minusDays(1)), 3, List.of(stops));
    }

    private static StopState stopOf(int sequence, String stopId, ApproachingVehicle... vehicles) {
        return new StopState(sequence, stopId, "정류장 " + sequence, BoardDirection.UP, true, List.of(vehicles));
    }

    private static ApproachingVehicle departureFull(String vehicleId, double fullProbability) {
        return new ApproachingVehicle(vehicleId, 2, new VehicleForecast.Available(1.0 - fullProbability, 12.0,
            "AFTER_ALIGHTING_ESTIMATE", "DEPARTURE_ZERO", fullProbability));
    }

    private static StopList listOf(StopEntry... entries) {
        return new Queried(List.of(entries), Coverage.COVERAGE_UNKNOWN);
    }

    private static StopEntry entry(String id, double arrivalMinutes, String vehicleId) {
        return new StopEntry(id, new Observed<>(Offset.ofMinutes(arrivalMinutes)), Optional.of(vehicleId));
    }

    private static ArrivalVisit visit(String stationId, int stopSequence) {
        return new ArrivalVisit(PUBLIC_ROUTE, SOURCE_ROUTE, ROUTE_VERSION, stopSequence, "UP",
            RECEIVED.minusSeconds(3600));
    }

    private static ArrivalReadRepository visitsOf(ArrivalVisit... visits) {
        return arrivalsOf(Map.of(STATION, List.of(visits)), Map.of());
    }

    private static ArrivalReadRepository arrivalsOf(Map<String, List<ArrivalVisit>> visits,
                                                    Map<String, ArrivalSnapshot> snapshots) {
        return new ArrivalReadRepository() {
            @Override
            public List<ArrivalVisit> visits(String stationId) {
                return visits.getOrDefault(stationId, List.of());
            }

            @Override
            public Optional<ArrivalSnapshot> latest(String stationId) {
                return Optional.ofNullable(snapshots.get(stationId));
            }
        };
    }

    private static ArrivalSnapshot snapshotOf(String stationId, int stopOrder, Prediction... predictions) {
        RouteArrival route = new RouteArrival(SOURCE_ROUTE, "9300", stopOrder, null, null, null, null,
            List.of(predictions));
        return new ArrivalSnapshot(UUID.nameUUIDFromBytes(stationId.getBytes()), stationId, RECEIVED,
            RECEIVED.plusSeconds(60), RECEIVED.plusSeconds(600), "query", List.of(route));
    }

    private static Prediction prediction(String vehicleId, int ordinal, int remainingSeconds) {
        return new Prediction(vehicleId, ordinal, remainingSeconds, null, 3, 10, null, null, null, null);
    }
}
