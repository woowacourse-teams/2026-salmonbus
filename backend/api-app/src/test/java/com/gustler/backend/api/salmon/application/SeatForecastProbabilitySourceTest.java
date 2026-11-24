package com.gustler.backend.api.salmon.application;

import static com.gustler.backend.api.salmon.domain.ActionFixture.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
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
import com.gustler.backend.api.salmon.application.ArrivalStopSource;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource;
import com.gustler.backend.api.salmon.application.WalkOption;
import com.gustler.backend.api.salmon.domain.ActionConfig;
import com.gustler.backend.api.salmon.domain.ActionDecider;
import com.gustler.backend.api.salmon.domain.ActionJudge;
import com.gustler.backend.api.salmon.domain.Fields;
import com.gustler.backend.api.salmon.domain.Probabilities;
import com.gustler.backend.api.salmon.domain.StopLists;
import com.gustler.backend.api.salmon.domain.Timing;
import com.gustler.backend.api.salmon.domain.Verdicts;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
        boardIs(boardOf(stopOf(
                5,
                STATION,
                departureFull("bus-1", 0.3),
                departureFull("bus-2", 0.6)
        )));
        StopLists.StopList list = listOf(entry("first", 4, "bus-1"), entry("second", 11, "bus-2"));

        Probabilities.ProbabilitySupply supply = source(visitsOf(visit(STATION, 5)))
            .probabilitiesFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    list,
                    NOW
            );

        assertThat(supply.inputs()).extracting(Probabilities.ProbabilityInput::attempt).containsExactly(
            "first",
            "second"
        );
        assertSoftly(softly -> {
            softly.assertThat(supply.inputs()).allSatisfy(input -> {
                softly.assertThat(input.tag()).isEqualTo(Probabilities.ValueTag.MARG);
                softly.assertThat(input.condition()).isEmpty();
                softly.assertThat(input.origin()).isEqualTo(Probabilities.ValueOrigin.SEAT_FORECAST);
            });
            softly.assertThat(supply.inputs().get(0).value()).isCloseTo(0.7, within(TOLERANCE));
            softly.assertThat(supply.inputs().get(1).value()).isCloseTo(0.4, within(TOLERANCE));
        });
    }

    @Test
    void 차량_ID가_빈_항목은_값_없음으로_둔다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));
        StopLists.StopList list = listOf(new StopLists.StopEntry(
            "unknown",
            new Fields.Observed<>(Timing.Offset.ofMinutes(4))
        ),
            entry("known", 11, "bus-1"));

        Probabilities.ProbabilitySupply supply = source(visitsOf(visit(STATION, 5)))
            .probabilitiesFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    list,
                    NOW
            );

        assertThat(supply.inputs()).extracting(Probabilities.ProbabilityInput::attempt).containsExactly("known");
    }

    @Test
    void 보드에_없는_차량과_예보를_제공하지_못한_차량의_항목은_값_없음으로_둔다() {
        boardIs(boardOf(stopOf(5, STATION, new ApproachingVehicle("bus-2", 3, new VehicleForecast.Unavailable()))));
        StopLists.StopList list = listOf(entry("absent", 4, "bus-1"), entry("unforecast", 11, "bus-2"));

        Probabilities.ProbabilitySupply supply = source(visitsOf(visit(STATION, 5)))
            .probabilitiesFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    list,
                    NOW
            );

        assertThat(supply.inputs()).isEmpty();
    }

    @Test
    void 같은_차량이라도_다른_정류장_방문을_목표로_한_예보는_쓰지_않는다() {
        boardIs(boardOf(stopOf(5, STATION), stopOf(6, "228000003", departureFull("bus-1", 0.3))));

        Probabilities.ProbabilitySupply supply = source(visitsOf(visit(STATION, 5)))
            .probabilitiesFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    listOf(entry("first", 4, "bus-1")),
                    NOW
            );

        assertThat(supply.inputs()).isEmpty();
    }

    @Test
    void 보드의_같은_정류장에_같은_차량_ID가_둘이면_어느_예보인지_정하지_않고_값_없음으로_둔다() {
        boardIs(boardOf(stopOf(
                5,
                STATION,
                departureFull("bus-1", 0.3),
                departureFull("bus-1", 0.9)
        )));

        Probabilities.ProbabilitySupply supply = source(visitsOf(visit(STATION, 5)))
            .probabilitiesFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    listOf(entry("first", 4, "bus-1")),
                    NOW
            );

        assertThat(supply.inputs()).isEmpty();
    }

    @Test
    void 예보를_낸_관측의_만료_시각이_판정_시각보다_이르면_값_없음이고_같으면_값을_낸다() {
        StopLists.StopList list = listOf(entry("first", 4, "bus-1"));
        SeatForecastProbabilitySource source = source(visitsOf(visit(STATION, 5)));

        boardIs(boardOf(NOW.minusMillis(1), stopOf(5, STATION, departureFull("bus-1", 0.3))));
        assertThat(source.probabilitiesFor(
                PUBLIC_ROUTE,
                STATION,
                5,
                list,
                NOW
        ).inputs()).isEmpty();

        boardIs(boardOf(NOW, stopOf(5, STATION, departureFull("bus-1", 0.3))));
        assertThat(source.probabilitiesFor(
                PUBLIC_ROUTE,
                STATION,
                5,
                list,
                NOW
        ).inputs()).hasSize(1);
    }

    @Test
    void 보드를_읽지_못하면_값_없음이다() {
        StopLists.StopList list = listOf(entry("first", 4, "bus-1"));
        SeatForecastProbabilitySource source = source(visitsOf(visit(STATION, 5)));

        for (RuntimeException failure : List.of(
                new NoRecentObservationException(),
                new ModelOutOfScopeException(),
                new RouteNotFoundException(),
                new ServiceUnavailableException(),
                new DataAccessResourceFailureException("connection refused")
        )) {
            willThrow(failure).given(boards).readBoard(new RouteId(PUBLIC_ROUTE));

            assertThat(source.probabilitiesFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    list,
                    NOW
            ).inputs())
                .as("%s", failure.getClass().getSimpleName()).isEmpty();
        }
    }

    @Test
    void 보드와_도착_방문의_노선_판본이_다르면_값_없음이다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));
        ArrivalVisit otherVersion = new ArrivalVisit(
                PUBLIC_ROUTE,
                SOURCE_ROUTE,
                ROUTE_VERSION + 1,
                5,
                "UP",
                RECEIVED.minusSeconds(3600)
        );

        Probabilities.ProbabilitySupply supply = source(visitsOf(otherVersion))
            .probabilitiesFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    listOf(entry("first", 4, "bus-1")),
                    NOW
            );

        assertThat(supply.inputs()).isEmpty();
    }

    @Test
    void 정류장_방문을_확인하지_못하면_값_없음이다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));

        Probabilities.ProbabilitySupply supply = source(visitsOf(visit(STATION, 12)))
            .probabilitiesFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    listOf(entry("first", 4, "bus-1")),
                    NOW
            );

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

        Probabilities.ProbabilitySupply supply = new SeatForecastProbabilitySource(boards, failing)
            .probabilitiesFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    listOf(entry("first", 4, "bus-1")),
                    NOW
            );

        assertThat(supply.inputs()).isEmpty();
    }

    @Test
    void 차량_ID가_있는_항목이_없으면_보드를_읽지_않는다() {
        SeatForecastProbabilitySource source = source(visitsOf(visit(STATION, 5)));

        assertSoftly(softly -> {
            softly.assertThat(source.probabilitiesFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    new StopLists.Unavailable(Fields.MissingReason.STALE),
                    NOW
            )
                .inputs()).isEmpty();
            softly.assertThat(source.probabilitiesFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    listOf(new StopLists.StopEntry("unknown", new Fields.Observed<>(Timing.Offset.ofMinutes(4)))),
                    NOW
            ).inputs()).isEmpty();
        });
        then(boards).should(never()).readBoard(any());
    }

    @Test
    void 항목마다_값과_그_값을_낸_예보의_확률_목표를_돌려준다() {
        boardIs(boardOf(stopOf(
                5,
                STATION,
                departureFull("bus-1", 0.3),
                departureFull("bus-2", 0.6)
        )));

        List<SeatForecastProbabilitySource.EntryLookup> lookups = lookupsFor(listOf(
            entry("first", 4, "bus-1"),
            entry("second", 11, "bus-2")
        ));

        assertThat(lookups).extracting(SeatForecastProbabilitySource.EntryLookup::entryId).containsExactly(
            "first",
            "second"
        );
        assertSoftly(softly -> {
            softly.assertThat(
                lookups.get(0).lookup()
            ).isInstanceOfSatisfying(SeatForecastProbabilitySource.Found.class, found -> {
                softly.assertThat(found.value()).isCloseTo(0.7, within(TOLERANCE));
                softly.assertThat(found.probabilityTarget()).isEqualTo("DEPARTURE_ZERO");
            });
            softly.assertThat(
                lookups.get(1).lookup()
            ).isInstanceOfSatisfying(SeatForecastProbabilitySource.Found.class, found -> {
                softly.assertThat(found.value()).isCloseTo(0.4, within(TOLERANCE));
                softly.assertThat(found.probabilityTarget()).isEqualTo("DEPARTURE_ZERO");
            });
        });
    }

    @Test
    void 차량_ID가_있는_항목이_없으면_모든_항목의_원인이_목록에_차량_ID_없음이고_보드를_읽지_않는다() {
        List<SeatForecastProbabilitySource.EntryLookup> lookups = lookupsFor(listOf(new StopLists.StopEntry(
            "first",
            new Fields.Observed<>(Timing.Offset.ofMinutes(4))
        ),
            new StopLists.StopEntry("second", new Fields.Observed<>(Timing.Offset.ofMinutes(11)))));

        assertSoftly(softly -> {
            softly.assertThat(lookups).extracting(SeatForecastProbabilitySource.EntryLookup::entryId).containsExactly(
                "first",
                "second"
            );
            softly.assertThat(lookups).extracting(SeatForecastProbabilitySource.EntryLookup::lookup).containsOnly(
                new SeatForecastProbabilitySource.NotFound(SeatForecastProbabilitySource.Cause.LIST_WITHOUT_VEHICLE_ID)
            );
        });
        then(boards).should(never()).readBoard(any());
    }

    @Test
    void 보드_읽기가_거부되면_모든_항목의_원인이_보드_읽기_실패이고_오류_코드를_함께_돌려준다() {
        StopLists.StopList list = listOf(entry("first", 4, "bus-1"),
            new StopLists.StopEntry("second", new Fields.Observed<>(Timing.Offset.ofMinutes(11))));

        Map<ErrorCode, ApiException> failures = Map.of(
                ErrorCode.ROUTE_NOT_FOUND,
                new RouteNotFoundException(),
                ErrorCode.MODEL_OUT_OF_SCOPE,
                new ModelOutOfScopeException(),
                ErrorCode.NO_RECENT_OBSERVATION,
                new NoRecentObservationException(),
                ErrorCode.SERVICE_UNAVAILABLE,
                new ServiceUnavailableException()
        );
        failures.forEach((code, failure) -> {
            willThrow(failure).given(boards).readBoard(new RouteId(PUBLIC_ROUTE));

            assertThat(lookupsFor(list)).as("%s", code).extracting(SeatForecastProbabilitySource.EntryLookup::lookup)
                .containsExactly(new SeatForecastProbabilitySource.NotFound(
                    SeatForecastProbabilitySource.Cause.BOARD_READ_FAILED,
                    Optional.of(code)
                ),
                    new SeatForecastProbabilitySource.NotFound(
                        SeatForecastProbabilitySource.Cause.BOARD_READ_FAILED,
                        Optional.of(code)
                    ));
        });
    }

    @Test
    void 보드나_정류장_방문의_DB_조회가_실패하면_모든_항목의_원인이_조회_실패다() {
        StopLists.StopList list = listOf(entry("first", 4, "bus-1"), entry("second", 11, "bus-2"));
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
            .lookupsFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    list,
                    NOW
            )).extracting(SeatForecastProbabilitySource.EntryLookup::lookup)
            .containsExactly(new SeatForecastProbabilitySource.NotFound(
                SeatForecastProbabilitySource.Cause.QUERY_FAILED
            ), new SeatForecastProbabilitySource.NotFound(SeatForecastProbabilitySource.Cause.QUERY_FAILED));

        willThrow(new DataAccessResourceFailureException("connection refused"))
            .given(boards).readBoard(new RouteId(PUBLIC_ROUTE));
        assertThat(lookupsFor(list)).extracting(SeatForecastProbabilitySource.EntryLookup::lookup)
            .containsExactly(new SeatForecastProbabilitySource.NotFound(
                SeatForecastProbabilitySource.Cause.QUERY_FAILED
            ), new SeatForecastProbabilitySource.NotFound(SeatForecastProbabilitySource.Cause.QUERY_FAILED));
    }

    @Test
    void 판정_시각이_보드_만료_시각을_지났으면_모든_항목의_원인이_보드_만료다() {
        boardIs(boardOf(NOW.minusMillis(1), stopOf(5, STATION, departureFull("bus-1", 0.3))));

        assertThat(lookupsFor(listOf(entry("first", 4, "bus-1"), entry("second", 11, "bus-2"))))
            .extracting(SeatForecastProbabilitySource.EntryLookup::lookup)
            .containsExactly(new SeatForecastProbabilitySource.NotFound(
                SeatForecastProbabilitySource.Cause.BOARD_STALE
            ), new SeatForecastProbabilitySource.NotFound(SeatForecastProbabilitySource.Cause.BOARD_STALE));
    }

    @Test
    void 정류장_방문을_찾지_못했거나_노선_판본이_다르면_모든_항목의_원인이_방문_불일치다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));
        StopLists.StopList list = listOf(entry("first", 4, "bus-1"), entry("second", 11, "bus-2"));
        ArrivalVisit otherVersion = new ArrivalVisit(
                PUBLIC_ROUTE,
                SOURCE_ROUTE,
                ROUTE_VERSION + 1,
                5,
                "UP",
                RECEIVED.minusSeconds(3600)
        );

        for (ArrivalReadRepository arrivals : List.of(visitsOf(visit(STATION, 12)), visitsOf(otherVersion))) {
            assertThat(source(arrivals).lookupsFor(
                    PUBLIC_ROUTE,
                    STATION,
                    5,
                    list,
                    NOW
            ))
                .extracting(SeatForecastProbabilitySource.EntryLookup::lookup)
                .containsExactly(new SeatForecastProbabilitySource.NotFound(
                    SeatForecastProbabilitySource.Cause.VISIT_NOT_MATCHED
                ), new SeatForecastProbabilitySource.NotFound(SeatForecastProbabilitySource.Cause.VISIT_NOT_MATCHED));
        }
    }

    @Test
    void 차량_ID가_없는_항목의_원인은_항목에_차량_ID_없음이다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-1", 0.3))));

        List<SeatForecastProbabilitySource.EntryLookup> lookups = lookupsFor(listOf(new StopLists.StopEntry(
            "unknown",
            new Fields.Observed<>(Timing.Offset.ofMinutes(4))
        ),
            entry("known", 11, "bus-1")));

        assertThat(lookups).hasSizeGreaterThan(1);
        assertSoftly(softly -> {
            softly.assertThat(lookups.get(0)).isEqualTo(new SeatForecastProbabilitySource.EntryLookup(
                "unknown",
                new SeatForecastProbabilitySource.NotFound(SeatForecastProbabilitySource.Cause.ENTRY_WITHOUT_VEHICLE_ID)
            ));
            softly.assertThat(lookups.get(1).lookup()).isInstanceOf(SeatForecastProbabilitySource.Found.class);
        });
    }

    @Test
    void 보드의_그_정류장_방문에_차량이_없는_항목의_원인은_보드에_차량_없음이다() {
        boardIs(boardOf(stopOf(5, STATION, departureFull("bus-2", 0.3)), stopOf(6, "228000003",
            departureFull("bus-1", 0.3))));

        assertThat(lookupsFor(listOf(entry(
            "first",
            4,
            "bus-1"
        )))).extracting(SeatForecastProbabilitySource.EntryLookup::lookup)
            .containsExactly(new SeatForecastProbabilitySource.NotFound(
                SeatForecastProbabilitySource.Cause.VEHICLE_NOT_ON_BOARD
            ));
    }

    @Test
    void 보드의_그_정류장_방문에_같은_차량_ID가_둘인_항목의_원인은_차량_중복이다() {
        boardIs(boardOf(stopOf(
                5,
                STATION,
                departureFull("bus-1", 0.3),
                departureFull("bus-1", 0.9)
        )));

        assertThat(lookupsFor(listOf(entry(
            "first",
            4,
            "bus-1"
        )))).extracting(SeatForecastProbabilitySource.EntryLookup::lookup)
            .containsExactly(new SeatForecastProbabilitySource.NotFound(
                SeatForecastProbabilitySource.Cause.VEHICLE_AMBIGUOUS
            ));
    }

    @Test
    void 보드가_예보를_제공하지_못한_차량의_항목은_원인이_예보_제공_불가다() {
        boardIs(boardOf(stopOf(5, STATION, new ApproachingVehicle("bus-1", 3, new VehicleForecast.Unavailable()))));

        assertThat(lookupsFor(listOf(entry(
            "first",
            4,
            "bus-1"
        )))).extracting(SeatForecastProbabilitySource.EntryLookup::lookup)
            .containsExactly(new SeatForecastProbabilitySource.NotFound(
                SeatForecastProbabilitySource.Cause.FORECAST_UNAVAILABLE
            ));
    }

    @Test
    void 오류_코드는_보드_읽기_실패_원인에만_붙는다() {
        assertThatThrownBy(
            () -> new SeatForecastProbabilitySource.NotFound(SeatForecastProbabilitySource.Cause.BOARD_READ_FAILED)
        )
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeatForecastProbabilitySource.NotFound(
            SeatForecastProbabilitySource.Cause.BOARD_STALE,
            Optional.of(ErrorCode.SERVICE_UNAVAILABLE)
        ))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 좌석_예보_값으로_판정하면_결과에_근사_표지가_남고_시도가_둘_이상이면_독립_가정_표지도_남는다() {
        ArrivalReadRepository arrivals = arrivalsOf(
            Map.of(
                    STATION,
                    List.of(visit(STATION, 5)),
                    UPSTREAM_STATION,
                    List.of(visit(UPSTREAM_STATION, 4))
            ),
            Map.of(
                    STATION,
                    snapshotOf(
                            STATION,
                            5,
                            prediction("bus-1", 1, 270),
                            prediction("bus-2", 2, 690)
                    ),
                    UPSTREAM_STATION,
                    snapshotOf(
                            UPSTREAM_STATION,
                            4,
                            prediction("bus-2", 1, 570),
                            prediction("bus-3", 2, 1230)
                    )
            ));
        boardIs(boardOf(
            stopOf(
                    4,
                    UPSTREAM_STATION,
                    departureFull("bus-2", 0.2),
                    departureFull("bus-3", 0.1)
            ),
            stopOf(
                    5,
                    STATION,
                    departureFull("bus-1", 0.7),
                    departureFull("bus-2", 0.6)
            )));
        ActionConfig config = config(15, 8);
        ArrivalStopSource stops = new ArrivalStopSource(arrivals, ArrivalStopSource.CoverageRule.NONE, config);
        SeatForecastProbabilitySource probabilities = new SeatForecastProbabilitySource(boards, arrivals);
        StopLists.StopList current = stops.arrivalsAt(
                PUBLIC_ROUTE,
                STATION,
                5,
                NOW
        );
        StopLists.StopList upstream = stops.arrivalsAt(
                PUBLIC_ROUTE,
                UPSTREAM_STATION,
                4,
                NOW
        );
        Probabilities.ProbabilitySupply currentSupply = probabilities.probabilitiesFor(
                PUBLIC_ROUTE,
                STATION,
                5,
                current,
                NOW
        );
        Probabilities.ProbabilitySupply upstreamSupply =
            probabilities.probabilitiesFor(
                    PUBLIC_ROUTE,
                    UPSTREAM_STATION,
                    4,
                    upstream,
                    NOW
            );
        ActionJudge.CandidateSource candidates = epoch -> CandidateAssembler.assemble(
                PUBLIC_ROUTE,
                NOW,
                List.of(new WalkOption(
                        UPSTREAM_STATION,
                        4,
                        WalkOption.Relation.BEFORE,
                        180
                )),
                stops,
                (routeId, option) -> Optional.of(0.0),
                upstreamSupply
        );

        ActionDecider.Decision decision = ActionDecider.decide(new ActionDecider.Sources() {
            @Override
            public ActionJudge.Baseline baseline(String epoch, Optional<StopLists.StopList> override) {
                return new ActionJudge.Baseline(epoch, override.orElse(current), currentSupply);
            }

            @Override
            public ActionJudge.CandidateSource candidates() {
                return candidates;
            }
        }, config);

        Verdicts.Verdict verdict = decision.verdict();
        assertSoftly(softly -> {
            softly.assertThat(currentSupply.inputs()).extracting(Probabilities.ProbabilityInput::value)
                .satisfiesExactly(value -> softly.assertThat(value).isCloseTo(0.3, within(TOLERANCE)),
                    value -> softly.assertThat(value).isCloseTo(0.4, within(TOLERANCE)));
            softly.assertThat(upstreamSupply.inputs()).extracting(Probabilities.ProbabilityInput::value)
                .satisfiesExactly(value -> softly.assertThat(value).isCloseTo(0.8, within(TOLERANCE)),
                    value -> softly.assertThat(value).isCloseTo(0.9, within(TOLERANCE)));
        });
        assertThat(verdict.stay().evaluation()).isInstanceOf(Verdicts.Evaluable.class);
        Verdicts.Evaluable stay = (Verdicts.Evaluable) verdict.stay().evaluation();
        assertSoftly(softly -> {
            softly.assertThat(verdict.stay().attempts()).extracting(Verdicts.Attempt::arrival)
                .containsExactly(Timing.Offset.ofMinutes(4), Timing.Offset.ofMinutes(11));
            softly.assertThat(stay.value()).isCloseTo(0.42, within(TOLERANCE));
            softly.assertThat(stay.assumptions()).containsExactly(
                Verdicts.Assumption.INDEP,
                Verdicts.Assumption.SEAT_FORECAST_PROXY
            );
        });
        assertThat(verdict.candidates()).isNotEmpty();
        Verdicts.CandidateRecord walk = verdict.candidates().get(0);
        assertThat(walk.outcome().evaluation()).isInstanceOf(Verdicts.Evaluable.class);
        Verdicts.Evaluable walked = (Verdicts.Evaluable) walk.outcome().evaluation();
        assertThat(verdict.delta()).isPresent();
        assertSoftly(softly -> {
            softly.assertThat(walk.outcome().attempts()).extracting(Verdicts.Attempt::arrival).containsExactly(
                Timing.Offset.ofMinutes(9)
            );
            softly.assertThat(walked.value()).isCloseTo(0.2, within(TOLERANCE));
            softly.assertThat(walked.assumptions()).containsExactly(Verdicts.Assumption.SEAT_FORECAST_PROXY);
            softly.assertThat(verdict.code()).isEqualTo(Verdicts.ReasonCode.GO);
            softly.assertThat(verdict.bestCandidate()).contains("walk-" + UPSTREAM_STATION + "-4");
            softly.assertThat(verdict.delta().orElseThrow()).isCloseTo(0.22, within(TOLERANCE));
        });
    }

    private void boardIs(Board board) {
        given(boards.readBoard(new RouteId(PUBLIC_ROUTE)))
            .willReturn(new BoardReading(board, 1L, Duration.ofSeconds(15)));
    }

    private List<SeatForecastProbabilitySource.EntryLookup> lookupsFor(StopLists.StopList list) {
        return source(visitsOf(visit(STATION, 5))).lookupsFor(
                PUBLIC_ROUTE,
                STATION,
                5,
                list,
                NOW
        );
    }

    private SeatForecastProbabilitySource source(ArrivalReadRepository arrivals) {
        return new SeatForecastProbabilitySource(boards, arrivals);
    }

    private static Board boardOf(StopState... stops) {
        return boardOf(RECEIVED.plusSeconds(300), stops);
    }

    private static Board boardOf(Instant staleAt, StopState... stops) {
        BoardRoute route = new BoardRoute(
                PUBLIC_ROUTE,
                "9300",
                "기점",
                "종점",
                RouteStatus.FORECAST_READY,
                null,
                List.of(new DirectionInfo(
                        BoardDirection.UP,
                        "종점 방면",
                        "기점",
                        "종점",
                        "05:00",
                        "22:30"
                )),
                Long.toString(ROUTE_VERSION),
                RouteHeadways.none()
        );
        OffsetDateTime stale = staleAt.atOffset(ZoneOffset.UTC);
        return new Board(
                route,
                stale.minusMinutes(5),
                stale,
                new ForecastModel(1, "release", stale.minusDays(1)),
                3,
                List.of(stops)
        );
    }

    private static StopState stopOf(final int sequence, String stopId, ApproachingVehicle... vehicles) {
        return new StopState(
                sequence,
                stopId,
                "정류장 " + sequence,
                BoardDirection.UP,
                true,
                List.of(vehicles)
        );
    }

    private static ApproachingVehicle departureFull(String vehicleId, final double fullProbability) {
        return new ApproachingVehicle(vehicleId, 2, new VehicleForecast.Available(
                1.0 - fullProbability,
                12.0,
                "AFTER_ALIGHTING_ESTIMATE",
                "DEPARTURE_ZERO",
                fullProbability
        ));
    }

    private static StopLists.StopList listOf(StopLists.StopEntry... entries) {
        return new StopLists.Queried(List.of(entries), StopLists.Coverage.COVERAGE_UNKNOWN);
    }

    private static StopLists.StopEntry entry(String id, final double arrivalMinutes, String vehicleId) {
        return new StopLists.StopEntry(
            id,
            new Fields.Observed<>(Timing.Offset.ofMinutes(arrivalMinutes)),
            Optional.of(vehicleId)
        );
    }

    private static ArrivalVisit visit(String stationId, final int stopSequence) {
        return new ArrivalVisit(
                PUBLIC_ROUTE,
                SOURCE_ROUTE,
                ROUTE_VERSION,
                stopSequence,
                "UP",
                RECEIVED.minusSeconds(3600)
        );
    }

    private static ArrivalReadRepository visitsOf(ArrivalVisit... visits) {
        return arrivalsOf(Map.of(STATION, List.of(visits)), Map.of());
    }

    private static ArrivalReadRepository arrivalsOf(
        Map<String, List<ArrivalVisit>> visits,
        Map<String, ArrivalSnapshot> snapshots
    ) {
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

    private static ArrivalSnapshot snapshotOf(
        String stationId,
        final int stopOrder,
        ArrivalSnapshot.Prediction... predictions
    ) {
        ArrivalSnapshot.RouteArrival route = new ArrivalSnapshot.RouteArrival(
                SOURCE_ROUTE,
                "9300",
                stopOrder,
                null,
                null,
                null,
                null,
                List.of(predictions)
        );
        return new ArrivalSnapshot(
                UUID.nameUUIDFromBytes(stationId.getBytes()),
                stationId,
                RECEIVED,
                RECEIVED.plusSeconds(60),
                RECEIVED.plusSeconds(600),
                "query",
                List.of(route)
        );
    }

    private static ArrivalSnapshot.Prediction prediction(
        String vehicleId,
        final int ordinal,
        final int remainingSeconds
    ) {
        return new ArrivalSnapshot.Prediction(
                vehicleId,
                ordinal,
                remainingSeconds,
                null,
                3,
                10,
                null,
                null,
                null,
                null
        );
    }
}
