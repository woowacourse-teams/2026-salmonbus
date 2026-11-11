package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 마감까지 그 정류장 방문에 닿는 차량을 도착 예측에서 찾는다.
 *
 * <p>마감은 판정 시각에 {@code horizon}을 더한 시각이고, 예상 도착 시각이 마감과 같으면 마감 안이다.
 * 예상 도착 시각이 판정 시각보다 이르면 도착 조회와 같이 없는 예측으로 본다.
 * 분 단위 예측은 예상 도착 시각의 앞뒤 1분 안에 마감이 있으면 마감 안인지 가를 수 없다.
 *
 * <p>도착 목록은 노선마다 두 대까지만 준다. 두 대가 모두 마감 안이면 전광판에만 보이는 뒤 차량도 마감 안에 올 수 있다.
 * 그 차량의 도착 시각은 맨 앞 차량이 지금 있는 정류장(앞 정류장)의 도착 목록에서 읽고, 앞 정류장에서 이 정류장까지
 * 걸리는 시간을 더해 추정한다. 걸리는 시간은 두 목록에 같이 보이는 차량이 있으면 그 차량의 두 예상 도착 시각의 차이다.
 * 같이 보이는 차량이 없으면, 맨 앞 차량이 앞 정류장에 있던 전광판 관측 시각부터 이 정류장에 닿는 예상 시각까지로 본다.
 */
@Component
public class SalmonDueArrivals {
    private static final Duration MINUTE_RESOLUTION = Duration.ofMinutes(1);
    private static final int LISTED_PER_ROUTE = 2;

    private final ArrivalReadRepository repository;
    private final ArrivalQueryService stations;
    private final SalmonActionSettings settings;

    public SalmonDueArrivals(ArrivalReadRepository repository, ArrivalQueryService stations,
                             SalmonActionSettings settings) {
        this.repository = repository;
        this.stations = stations;
        this.settings = settings;
    }

    /**
     * 마감 안에 닿는 차량. listed는 이 정류장의 도착 목록에 보이는 차량이고, hidden은 전광판에만 보여 도착을 앞 정류장에서
     * 이어 붙인 차량이다. hidden의 도착 시각은 추정이라 둘을 섞지 않는다.
     */
    public record Due(List<String> listed, List<String> hidden) {
        public Due {
            listed = List.copyOf(listed);
            hidden = List.copyOf(hidden);
        }

        static Due none() {
            return new Due(List.of(), List.of());
        }
    }

    /**
     * 마감 안에 닿는 차량의 ID를 돌려준다. 이 정류장의 스냅샷을 쓸 수 없거나, 목록의 예측 가운데 마감 안인지 가를 수 없는
     * 예측이 있거나, 마감 안에 닿는 차량 가운데 ID가 없는 차량이 있으면 listed가 비어 있다. 마감 안에 닿는 차량이 없을 때도
     * 비어 있다. 전광판에만 보이는 뒤 차량은 마감 안이 확실할 때만 hidden에 넣는다.
     */
    public Due vehicles(String routeId, Board board, String stationId, int stopSequence, Instant decisionAt) {
        Optional<List<Expected>> listed = repository.latest(stationId)
            .flatMap(snapshot -> expectedAt(snapshot, routeId, board, stationId, stopSequence, decisionAt));
        if (listed.isEmpty()) {
            return Due.none();
        }
        Instant deadline = decisionAt.plus(settings.horizon());
        List<String> due = new ArrayList<>();
        for (Expected expected : listed.get()) {
            if (expected.surelyAfter(deadline)) {
                continue;
            }
            if (!expected.surelyBy(deadline) || expected.vehicleId().isEmpty()) {
                return Due.none();
            }
            due.add(expected.vehicleId().get());
        }
        if (listed.get().size() == LISTED_PER_ROUTE && due.size() == LISTED_PER_ROUTE) {
            return new Due(due, hiddenDue(routeId, board, stationId, stopSequence, listed.get(), decisionAt, deadline));
        }
        return new Due(due, List.of());
    }

    private List<String> hiddenDue(String routeId, Board board, String stationId, int stopSequence,
                                   List<Expected> listed, Instant decisionAt, Instant deadline) {
        List<ApproachingVehicle> approaching = stopAt(board, stopSequence)
            .filter(stop -> stop.stopId().equals(stationId))
            .map(StopState::approachingVehicles)
            .orElse(List.of());
        List<String> hidden = approaching.stream()
            .map(ApproachingVehicle::vehicleId)
            .filter(vehicleId -> vehicleId != null && listed.stream().noneMatch(expected -> expected.is(vehicleId)))
            .toList();
        Expected front = listed.stream().min(Comparator.comparing(Expected::at)).orElseThrow();
        Optional<ApproachingVehicle> frontOnBoard = approaching.stream()
            .filter(vehicle -> vehicle.vehicleId() != null && front.is(vehicle.vehicleId()))
            .findFirst();
        if (hidden.isEmpty() || frontOnBoard.isEmpty() || frontOnBoard.get().remainingStops() < 1) {
            return List.of();
        }
        int stopsAhead = frontOnBoard.get().remainingStops();
        Optional<StopState> frontStop = stopAt(board, stopSequence - stopsAhead);
        if (frontStop.isEmpty()) {
            return List.of();
        }
        List<Expected> upstream = upstreamExpected(routeId, board, frontStop.get(), decisionAt);
        Optional<Leg> leg = Leg.seenAtBoth(upstream, listed)
            .or(() -> Leg.sinceObserved(board.observedAt().toInstant(), front, stopsAhead));
        if (leg.isEmpty()) {
            return List.of();
        }
        List<String> due = new ArrayList<>();
        for (String vehicleId : hidden) {
            upstream.stream()
                .filter(expected -> expected.is(vehicleId))
                .findFirst()
                .filter(expected -> expected.after(leg.get()).surelyBy(deadline))
                .ifPresent(expected -> due.add(vehicleId));
        }
        return due;
    }

    private List<Expected> upstreamExpected(String routeId, Board board, StopState stop, Instant decisionAt) {
        try {
            return Optional.ofNullable(stations.getForKnownStation(stop.stopId()).snapshot())
                .flatMap(snapshot -> expectedAt(snapshot, routeId, board, stop.stopId(), stop.sequence(), decisionAt))
                .orElse(List.of());
        } catch (ApiException | DataAccessException unavailable) {
            return List.of();
        }
    }

    /** 그 정류장 방문의 아직 지나지 않은 예측. 스냅샷이 만료됐거나 전광판과 노선 판본이 다르면 빈 값이다. */
    private Optional<List<Expected>> expectedAt(ArrivalSnapshot snapshot, String routeId, Board board,
                                                String stationId, int stopSequence, Instant decisionAt) {
        if (!SalmonObservationFreshness.usable(decisionAt, snapshot.staleAt())) {
            return Optional.empty();
        }
        Optional<ArrivalVisit> visit = repository.visits(stationId).stream()
            .filter(candidate -> candidate.routeId().equals(routeId)
                && candidate.stopSequence() == stopSequence
                && Long.toString(candidate.referenceVersionId()).equals(board.route().referenceVersionId()))
            .findFirst();
        if (visit.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshot.routes().stream()
            .filter(route -> visit.get().matches(route.routeId(), route.sourceStopOrder(), snapshot.receivedAt()))
            .findFirst()
            .map(RouteArrival::arrivals)
            .orElse(List.of())
            .stream()
            .map(prediction -> ArrivalPrediction.at(prediction, snapshot.receivedAt(), decisionAt))
            .filter(ArrivalPrediction::available)
            .map(Expected::of)
            .toList());
    }

    private static Optional<StopState> stopAt(Board board, int sequence) {
        List<StopState> stops = board.stops().stream().filter(stop -> stop.sequence() == sequence).toList();
        return stops.size() == 1 ? Optional.of(stops.getFirst()) : Optional.empty();
    }

    /** 차량 한 대의 예상 도착 시각과, 그 시각이 앞뒤로 어긋날 수 있는 폭. */
    private record Expected(Optional<String> vehicleId, Instant at, Duration margin) {
        static Expected of(ArrivalPrediction arrival) {
            Prediction source = arrival.source();
            return new Expected(
                Optional.ofNullable(source.vehicleId()).filter(vehicleId -> !vehicleId.isBlank()),
                arrival.expectedArrivalAt(),
                source.sourceRemainingSeconds() == null ? MINUTE_RESOLUTION : Duration.ZERO);
        }

        boolean surelyBy(Instant deadline) {
            return !at.plus(margin).isAfter(deadline);
        }

        boolean surelyAfter(Instant deadline) {
            return at.minus(margin).isAfter(deadline);
        }

        boolean is(String id) {
            return vehicleId.filter(id::equals).isPresent();
        }

        Expected after(Leg leg) {
            return new Expected(vehicleId, at.plus(leg.travel()), margin.plus(leg.margin()));
        }
    }

    /** 앞 정류장에서 이 정류장까지 걸리는 시간과, 그 시간이 어긋날 수 있는 폭. */
    private record Leg(Duration travel, Duration margin) {
        /** 두 목록에 같이 보이는 차량의 두 예상 도착 시각의 차이. */
        static Optional<Leg> seenAtBoth(List<Expected> upstream, List<Expected> here) {
            for (Expected arrival : here) {
                Optional<Expected> earlier = upstream.stream()
                    .filter(expected -> arrival.vehicleId().filter(expected::is).isPresent())
                    .findFirst()
                    .filter(expected -> !expected.at().isAfter(arrival.at()));
                if (earlier.isPresent()) {
                    return Optional.of(new Leg(Duration.between(earlier.get().at(), arrival.at()),
                        earlier.get().margin().plus(arrival.margin())));
                }
            }
            return Optional.empty();
        }

        /**
         * 맨 앞 차량은 전광판 관측 시각에 앞 정류장에 있었거나 이미 떠났다. 떠났다면 실제 구간 시간은 더 길다.
         * 그 차이를 정류장 한 구간의 평균 시간으로 보고 여유에 넣는다. 앞 정류장 바로 다음 구간이 평균보다 훨씬 길면
         * 이 여유로는 모자란다.
         */
        static Optional<Leg> sinceObserved(Instant observedAt, Expected front, int stopsAhead) {
            Duration travel = Duration.between(observedAt, front.at());
            if (travel.isNegative()) {
                return Optional.empty();
            }
            return Optional.of(new Leg(travel, front.margin().plus(travel.dividedBy(stopsAhead))));
        }
    }
}
