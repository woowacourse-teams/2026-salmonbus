package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.board.application.BoardCachePolicy;
import com.gustler.backend.api.board.application.BoardFreshnessPolicy;
import com.gustler.backend.api.board.application.BoardQueryRepository;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.application.BoardReadCache;
import com.gustler.backend.api.board.application.BoardReading;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.domain.RouteStatus;
import com.gustler.backend.api.salmon.application.ArrivalStopSource;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.Cause;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.EntryLookup;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.Found;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.Lookup;
import com.gustler.backend.api.salmon.application.SeatForecastProbabilitySource.NotFound;
import com.gustler.backend.api.salmon.domain.ActionConfig;
import com.gustler.backend.api.salmon.domain.ActionDecider;
import com.gustler.backend.api.salmon.domain.ActionJudge.Baseline;
import com.gustler.backend.api.salmon.domain.ActionJudge.CandidateSource;
import com.gustler.backend.api.salmon.domain.ActionJudge.Listed;
import com.gustler.backend.api.salmon.domain.Probabilities.ProbabilitySupply;
import com.gustler.backend.api.salmon.domain.StopLists.StopEntry;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import com.gustler.backend.api.salmon.domain.Verdicts.Attempt;
import com.gustler.backend.api.salmon.domain.Verdicts.Verdict;
import com.gustler.backend.api.salmon.offline.PassOutcome.Seats;
import com.gustler.backend.api.salmon.offline.PassOutcome.Unknown;
import com.gustler.backend.api.salmon.offline.RunSettings.SettingSet;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 설정 묶음 하나로 대기 판정을 돌린다. 도착 목록, 좌석 예보 확률, 보드 조립, 판정은 제품 클래스를 그대로 쓰고,
 * DB 읽기만 판정 시각 기준 어댑터로 바꿔 끼운다. 후보는 연결하지 않아 후보 조회는 언제나 빈 목록을 돌려준다.
 */
final class StayRun implements AutoCloseable {
    /** 도착 항목 하나. order는 도착이 이른 순서(1부터)이고, 결과 파일에서 차량 ID 대신 쓴다. */
    record EntryRecord(int order, boolean attempted, Lookup lookup, Optional<BoardAbsence> absence,
                       PassOutcome outcome) {
        Optional<Double> chance() {
            return lookup instanceof Found found ? Optional.of(found.value()) : Optional.empty();
        }
    }

    /** 보드가 그 정류장 방문에 담은 차량 가운데 예보가 붙은 차량 한 대. 가까운 순서이고 차량 ID는 결과에 싣지 않는다. */
    record BoardVehicle(String vehicleKey, double chance, PassOutcome outcome) {
    }

    /**
     * 판정 시각에 그 노선의 보드를 읽을 수 있었는지와, 읽었다면 그 정류장 방문에 담긴 차량 수와 예보가 붙은 차량 수.
     * 판정 입력이 아니라 실행기의 진단이다. 도착 목록이 비어 제품 코드가 보드를 읽지 않은 판정에서도 센다.
     * status는 읽었으면 READ(그 판본에 그때까지 발행이 하나도 없어 예보 없는 보드였으면 READ_UNPUBLISHED), 그 판본에
     * 그 순번의 정류장이 없으면 STOP_NOT_FOUND, 보드가 거부했으면 그 오류 코드다.
     */
    record BoardCheck(String status, int vehicles, int forecasts, List<BoardVehicle> forecastVehicles) {
        static final String READ = "READ";
        static final String READ_UNPUBLISHED = "READ_UNPUBLISHED";

        boolean read() {
            return status.equals(READ) || status.equals(READ_UNPUBLISHED);
        }

        static BoardCheck refused(String status) {
            return new BoardCheck(status, 0, 0, List.of());
        }
    }

    record DecisionRecord(DecisionPoint point, boolean routeIdsDiffer, StopList list, List<EntryRecord> entries,
                          Verdict verdict, BoardCheck board, List<Seats> windowPassages) {
    }

    private static final CandidateSource NO_CANDIDATES =
        epoch -> new Listed(List.of(), ProbabilitySupply.none(), Optional.empty());

    private final AsOfClock clock = new AsOfClock(Instant.EPOCH);
    private final AnnotationConfigApplicationContext boardBeans = new AnnotationConfigApplicationContext();
    private final ActionConfig config;
    private final BoardQueryRepository boardRepository;
    private final BoardQueryService boards;
    private final ArrivalStopSource arrivals;
    private final SeatForecastProbabilitySource probabilities;
    private final Map<String, String> sourceRouteIds;
    private final AsOfArrivalReadRepository visits;
    private final ObservationTrail trail;
    private final JdbcClient jdbc;
    private final Map<Long, Optional<Instant>> generatedAtByBatch = new HashMap<>();

    StayRun(JdbcClient jdbc, SettingSet settings) {
        this.jdbc = jdbc;
        config = settings.actionConfig();
        trail = new ObservationTrail(jdbc);
        boardRepository = new AsOfBoardQueryRepository(jdbc, clock);
        boards = boardServiceOf(boardRepository);
        AsOfArrivalReadRepository arrivalRepository = new AsOfArrivalReadRepository(jdbc, new ObjectMapper(), clock);
        visits = arrivalRepository;
        arrivals = new ArrivalStopSource(arrivalRepository, settings.coverageRule(), config);
        probabilities = new SeatForecastProbabilitySource(boards, arrivalRepository);
        sourceRouteIds = jdbc.sql("SELECT public_route_id, source_route_id FROM route").query(
            (row, index) -> Map.entry(row.getString(1), row.getString(2))).list().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // BoardQueryService와 그 정책 클래스를 제품의 생성자 그대로 조립한다. 시계와 저장소만 실행기 것을 준다.
    private BoardQueryService boardServiceOf(BoardQueryRepository repository) {
        boardBeans.registerBean(Clock.class, () -> clock);
        boardBeans.registerBean(BoardQueryRepository.class, () -> repository);
        boardBeans.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        boardBeans.register(BoardFreshnessPolicy.class, BoardCachePolicy.class, BoardReadCache.class,
            BoardQueryService.class);
        boardBeans.refresh();
        return boardBeans.getBean(BoardQueryService.class);
    }

    DecisionRecord decide(DecisionPoint point) {
        clock.moveTo(point.at());
        StopList list = arrivals.arrivalsAt(point.routeId(), point.stationId(), point.stopSequence(), point.at());
        List<EntryLookup> lookups = probabilities.lookupsFor(point.routeId(), point.stationId(), point.stopSequence(),
            list, point.at());
        ProbabilitySupply supply = probabilities.probabilitiesFor(point.routeId(), point.stationId(),
            point.stopSequence(), list, point.at());
        Verdict verdict = ActionDecider.decide(new ActionDecider.Sources() {
            @Override
            public Baseline baseline(String epoch, Optional<StopList> override) {
                return new Baseline(epoch, override.orElse(list), supply);
            }

            @Override
            public CandidateSource candidates() {
                return NO_CANDIDATES;
            }
        }, config).verdict();
        String sourceRouteId = sourceRouteIds.get(point.routeId());
        Optional<Long> versionId = visits.visits(point.stationId()).stream()
            .filter(visit -> visit.routeId().equals(point.routeId()) && visit.stopSequence() == point.stopSequence())
            .map(ArrivalVisit::referenceVersionId).findFirst();
        List<Seats> windowPassages = versionId.map(version -> trail.passagesWithin(version, point.stopSequence(),
            point.at(), Duration.ofMillis(config.horizon().millis()))).orElse(List.of());
        return new DecisionRecord(point, sourceRouteId != null && !sourceRouteId.equals(point.routeId()), list,
            entriesOf(point, versionId, list, lookups, verdict), verdict, boardCheckOf(point), windowPassages);
    }

    private BoardCheck boardCheckOf(DecisionPoint point) {
        try {
            BoardReading reading = boards.readBoard(new RouteId(point.routeId()));
            Board board = reading.board();
            // 발행이 하나도 없는 판본은 보드가 예보 없이 가장 늦은 정상 묶음을 보여 준다. 입력이 잘려 발행이 빠진 경우와
            // 구분할 수 있게 따로 센다.
            String status = board.route().status() == RouteStatus.PREPARING ? BoardCheck.READ_UNPUBLISHED
                : BoardCheck.READ;
            return board.stops().stream()
                .filter(stop -> stop.sequence() == point.stopSequence()).findFirst()
                .map(stop -> {
                    List<BoardVehicle> forecastVehicles = forecastVehiclesOf(reading, stop);
                    return new BoardCheck(status, stop.approachingVehicles().size(),
                        forecastVehicles.size(), forecastVehicles);
                })
                .orElse(BoardCheck.refused("STOP_NOT_FOUND"));
        } catch (ApiException refused) {
            return BoardCheck.refused(refused.code().name());
        }
    }

    // 예보는 보드의 관측 묶음에서 나왔으므로, 실제 결과는 그 묶음의 관측 시각 뒤에서 찾고 예보 계산 시각까지의 관측은
    // 건너뛴다(제품 정산과 같은 출발점).
    private List<BoardVehicle> forecastVehiclesOf(BoardReading reading, StopState stop) {
        Board board = reading.board();
        long versionId = Long.parseLong(board.route().referenceVersionId());
        Optional<Instant> generatedAt = generatedAtByBatch.computeIfAbsent(reading.observationBatchId(),
            batchId -> jdbc.sql("SELECT generated_at FROM forecast_publication WHERE source_batch_id = ?")
                .param(batchId).query((row, index) -> Optional.ofNullable(row.getTimestamp(1)))
                .optional().flatMap(found -> found).map(Timestamp::toInstant));
        List<BoardVehicle> vehicles = new ArrayList<>();
        for (ApproachingVehicle vehicle : stop.approachingVehicles()) {
            if (vehicle.forecast() instanceof VehicleForecast.Available available && vehicle.vehicleId() != null) {
                vehicles.add(new BoardVehicle(versionId + ":" + stop.sequence() + ":" + vehicle.vehicleId(),
                    available.seatAvailableProbability(), trail.outcomeOf(versionId,
                        Optional.of(vehicle.vehicleId()), stop.sequence(), board.observedAt().toInstant(),
                        generatedAt)));
            }
        }
        return vehicles;
    }

    private List<EntryRecord> entriesOf(DecisionPoint point, Optional<Long> versionId, StopList list,
                                        List<EntryLookup> lookups, Verdict verdict) {
        Set<String> attempted = verdict.stay().attempts().stream().map(Attempt::entryId).collect(Collectors.toSet());
        Map<String, Lookup> lookupByEntry = lookups.stream()
            .collect(Collectors.toMap(EntryLookup::entryId, EntryLookup::lookup));
        List<StopEntry> ordered = list.entries().stream()
            .sorted(Comparator.comparing((StopEntry entry) -> entry.arrival().present().orElseThrow())
                .thenComparing(StopEntry::id)).toList();
        List<EntryRecord> entries = new ArrayList<>();
        for (StopEntry entry : ordered) {
            Lookup lookup = lookupByEntry.get(entry.id());
            PassOutcome outcome = versionId.map(version -> trail.outcomeOf(version, entry.vehicleId(),
                point.stopSequence(), point.at(), Optional.empty())).orElse(PassOutcome.unknown(Unknown.NO_VISIT));
            entries.add(new EntryRecord(entries.size() + 1, attempted.contains(entry.id()), lookup,
                absenceOf(point, entry, lookup), outcome));
        }
        return entries;
    }

    private Optional<BoardAbsence> absenceOf(DecisionPoint point, StopEntry entry, Lookup lookup) {
        if (!(lookup instanceof NotFound notFound) || notFound.cause() != Cause.VEHICLE_NOT_ON_BOARD) {
            return Optional.empty();
        }
        int vehiclesOnBoard = boards.readBoard(new RouteId(point.routeId())).board().stops().stream()
            .filter(stop -> stop.sequence() == point.stopSequence())
            .mapToInt(stop -> stop.approachingVehicles().size()).sum();
        return Optional.of(BoardAbsence.of(boardRepository, point.routeId(), point.stopSequence(),
            entry.vehicleId().orElseThrow(), vehiclesOnBoard));
    }

    @Override
    public void close() {
        boardBeans.close();
    }
}
