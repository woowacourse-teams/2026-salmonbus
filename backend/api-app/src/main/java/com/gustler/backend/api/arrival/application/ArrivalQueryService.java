package com.gustler.backend.api.arrival.application;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.ArrivalQuery.Failure;
import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 현재 정류장 방문 판본과 수신 결과를 조회하고 필요하면 Worker 갱신을 요청한다.
 * 내부 HTTP 대기 중 DB 트랜잭션을 유지하지 않는다.
 *
 * <p>쓸 수 있는 수신본이 있으면 갱신 요청은 {@link ArrivalRefreshDispatcher}에 맡겨 응답을 막지 않는다.
 * 쓸 수 있는 수신본이 없으면 지금처럼 요청 스레드에서 보내고, 대기 자리를 얻었으면 첫 결과를 제한된 시간 기다린다.
 */
@Service
public class ArrivalQueryService {
    private static final Duration[] LATENCY_BUCKETS = {
        Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2)
    };
    private final ArrivalReadRepository repository;
    private final ArrivalRefreshGateway gateway;
    private final ArrivalFreshnessPolicy freshness;
    private final Clock clock;
    private final ArrivalFirstResultPolicy firstResult;
    private final ArrivalRefreshDispatcher dispatcher;
    private final MeterRegistry meters;

    public ArrivalQueryService(
        ArrivalReadRepository repository,
        ArrivalRefreshGateway gateway,
        ArrivalFreshnessPolicy freshness,
        Clock clock
    ) {
        this(repository, gateway, freshness, clock, new ArrivalFirstResultPolicy(Duration.ZERO, 32));
    }

    /**
     * 갱신 요청을 모두 요청 스레드에서 보내는 조회 서비스를 만든다.
     */
    public ArrivalQueryService(
        ArrivalReadRepository repository,
        ArrivalRefreshGateway gateway,
        ArrivalFreshnessPolicy freshness,
        Clock clock,
        ArrivalFirstResultPolicy firstResult
    ) {
        this(repository, gateway, freshness, clock, firstResult, new SimpleMeterRegistry());
    }

    private ArrivalQueryService(
        ArrivalReadRepository repository,
        ArrivalRefreshGateway gateway,
        ArrivalFreshnessPolicy freshness,
        Clock clock,
        ArrivalFirstResultPolicy firstResult,
        MeterRegistry meters
    ) {
        this(repository, gateway, freshness, clock, firstResult,
            ArrivalRefreshDispatcher.synchronous(gateway, clock, meters), meters);
    }

    @Autowired
    public ArrivalQueryService(
        ArrivalReadRepository repository,
        ArrivalRefreshGateway gateway,
        ArrivalFreshnessPolicy freshness,
        Clock clock,
        ArrivalFirstResultPolicy firstResult,
        ArrivalRefreshDispatcher dispatcher,
        MeterRegistry meters
    ) {
        this.firstResult = firstResult;
        this.repository = repository;
        this.gateway = gateway;
        this.freshness = freshness;
        this.clock = clock;
        this.dispatcher = dispatcher;
        this.meters = meters;
    }

    // 내부 HTTP 요청 중에 API의 DB 트랜잭션/연결을 유지하지 않는다.
    /**
     * 조회 대상을 검증하고 필요한 수집을 요청한다. 첫 유효 결과는 제한된 시간만 기다리며 사용할 결과가 없으면 UNAVAILABLE을 반환한다.
     */
    public StationArrivalsOverview get(ArrivalQuery query) {
        return read(query, true).overview();
    }

    /**
     * 정류장 지도 상세가 입구 확인을 마친 정류장을 조회한다. 우리 노선 방문이 없는 정류장도 수신본을 읽는다.
     * 기존 정류장 도착정보 API의 입구 조건(우리 노선 방문이 있어야 함)은 바꾸지 않는다.
     */
    public StationArrivalsOverview getForKnownStation(String stationId) {
        return readKnownStation(stationId).overview();
    }

    /**
     * {@link #getForKnownStation}과 같이 조회하고, 그 조회가 도착정보를 고르는 데 쓴 우리 노선 방문을 함께 돌려준다.
     * 정류장 상세가 방문을 다시 읽지 않고 같은 방문으로 노선 보드를 고르게 한다.
     */
    public KnownStationArrivals readKnownStation(String stationId) {
        return read(new ArrivalQuery(stationId, null, null, null), false);
    }

    private KnownStationArrivals read(ArrivalQuery query, final boolean requireVisit) {
        final long startedAt = System.nanoTime();
        List<ArrivalVisit> visits = repository.visits(query.stationId());
        validateTarget(query, visits, requireVisit);

        ArrivalSnapshot snapshot = repository.latest(query.stationId()).orElse(null);
        final boolean waiting = !freshness.usable(snapshot, clock.instant()) && firstResult.tryAcquire();
        RefreshRequest request;
        try {
            request = refreshIfDue(query, snapshot, waiting);
            ArrivalRefresh refresh = request.refresh();
            if (shouldReloadAfterWaiting(waiting, refresh)) {
                snapshot = repository.latest(query.stationId()).orElse(snapshot);
                if (freshness.usable(snapshot, clock.instant())) {
                    // 대기 중 바뀐 판본을 다시 확인해 새 수신을 이전 판본에 연결하지 않는다.
                    visits = repository.visits(query.stationId());
                    validateTarget(query, visits, requireVisit);
                    if (!freshness.refreshDue(snapshot, clock.instant())) {
                        request = new RefreshRequest(request.mode(),
                            new ArrivalRefresh(Status.IDLE, null, snapshot.refreshAfterAt()));
                    }
                }
            }
        } finally {
            if (waiting) {
                firstResult.release();
            }
        }

        StationArrivalsOverview overview = overviewOf(query, visits, snapshot, request.refresh());
        recordRead(request.mode(), overview, startedAt);
        return new KnownStationArrivals(overview, visits);
    }

    private StationArrivalsOverview overviewOf(
        ArrivalQuery query,
        List<ArrivalVisit> visits,
        ArrivalSnapshot snapshot,
        ArrivalRefresh refresh
    ) {
        Instant now = clock.instant();
        if (!freshness.usable(snapshot, now)) {
            return new StationArrivalsOverview(query.stationId(), now, ArrivalAvailability.UNAVAILABLE,
                unavailableReason(snapshot), snapshot, refresh, List.of(), Duration.ZERO);
        }

        List<RouteArrivals> routes = selectRoutes(query, visits, snapshot, now);
        ArrivalAvailability status = availability(routes, snapshot);
        String reason = unavailableReason(status, routes);
        return new StationArrivalsOverview(query.stationId(), now, status, reason, snapshot, refresh, routes,
            freshness.cacheMaxAge(snapshot, now));
    }

    private void recordRead(String mode, StationArrivalsOverview overview, final long startedAt) {
        Timer.builder("salmonbus.arrival.query.read")
            .description("도착정보 조회 시간. refresh는 Worker 갱신 요청 방식, usable은 응답에 쓸 수 있는 수신본이 있었는지다")
            .tag("refresh", mode)
            .tag("usable", Boolean.toString(freshness.usable(overview.snapshot(), overview.generatedAt())))
            .serviceLevelObjectives(LATENCY_BUCKETS)
            .register(meters)
            .record(Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private static boolean shouldReloadAfterWaiting(boolean waiting, ArrivalRefresh refresh) {
        if (!waiting) {
            return false;
        }
        return isRefreshPending(refresh) || "NOT_DUE".equals(refresh.reason());
    }

    private static boolean isRefreshPending(ArrivalRefresh refresh) {
        Status status = refresh.status();
        if (status == Status.ACCEPTED || status == Status.IN_PROGRESS) {
            return true;
        }
        return status == Status.IDLE;
    }

    private RefreshRequest refreshIfDue(ArrivalQuery query, ArrivalSnapshot snapshot, final boolean waiting) {
        Instant now = clock.instant();
        if (!freshness.refreshDue(snapshot, now)) {
            return new RefreshRequest("none", new ArrivalRefresh(Status.IDLE, null, snapshot.refreshAfterAt()));
        }
        if (waiting) {
            return new RefreshRequest("wait", timed("wait",
                () -> gateway.request(query.stationId(), firstResult.maximumWait())));
        }
        if (freshness.usable(snapshot, now)) {
            return new RefreshRequest("dispatch", dispatcher.request(query.stationId(), snapshot));
        }
        return new RefreshRequest("immediate", timed("immediate", () -> gateway.request(query.stationId())));
    }

    private ArrivalRefresh timed(String mode, Supplier<ArrivalRefresh> request) {
        final long startedAt = System.nanoTime();
        ArrivalRefresh reply = request.get();
        String status = "NONE";
        if (reply != null && reply.status() != null) {
            status = reply.status().name();
        }
        Timer.builder("salmonbus.arrival.refresh.request")
            .description("API가 Worker에 보낸 도착정보 갱신 요청 시간")
            .tag("mode", mode)
            .tag("status", status)
            .serviceLevelObjectives(LATENCY_BUCKETS)
            .register(meters)
            .record(Duration.ofNanos(System.nanoTime() - startedAt));
        return reply;
    }

    /**
     * 이번 조회가 Worker 갱신을 어떻게 요청했는지와 응답에 실을 갱신 상태다. mode는 none(갱신 시점 전), wait(첫 결과 대기),
     * dispatch(쓸 수 있는 수신본이 있어 {@link ArrivalRefreshDispatcher}에 맡김), immediate(요청 스레드에서 접수만) 중 하나다.
     */
    private record RefreshRequest(String mode, ArrivalRefresh refresh) {
    }

    private static String unavailableReason(ArrivalSnapshot snapshot) {
        if (snapshot == null) {
            return "NOT_COLLECTED";
        }
        return "STALE";
    }

    private static String unavailableReason(ArrivalAvailability status, List<RouteArrivals> routes) {
        if (status != ArrivalAvailability.UNAVAILABLE) {
            return null;
        }
        if (routes.isEmpty()) {
            return "NO_VERIFIED_ROUTE_MATCH";
        }
        return "PREDICTION_ELAPSED";
    }

    private static void validateTarget(ArrivalQuery query, List<ArrivalVisit> visits, final boolean requireVisit) {
        if (visits.isEmpty() && requireVisit) {
            throw new Failure(ErrorCode.STATION_NOT_FOUND);
        }
        if (query.referenceVersionId() != null && visits.stream().noneMatch(visit -> matchesReference(query, visit))) {
            throw new Failure(ErrorCode.REFERENCE_VERSION_CONFLICT);
        }
        if (query.routeId() != null && visits.stream().noneMatch(visit -> matchesRoute(query, visit))) {
            throw new Failure(ErrorCode.ROUTE_NOT_FOUND);
        }
    }

    private static boolean matchesReference(ArrivalQuery query, ArrivalVisit visit) {
        if (!matchesRoute(query, visit)) {
            return false;
        }
        if (visit.referenceVersionId() != query.referenceVersionId()) {
            return false;
        }
        return visit.stopSequence() == query.stopSequence();
    }

    private static boolean matchesRoute(ArrivalQuery query, ArrivalVisit visit) {
        return visit.routeId().equals(query.routeId());
    }

    private static List<RouteArrivals> selectRoutes(
        ArrivalQuery query,
        List<ArrivalVisit> visits,
        ArrivalSnapshot snapshot,
        Instant now
    ) {
        return snapshot.routes()
            .stream()
            .map(route -> selectRoute(query, visits, snapshot, route, now))
            .flatMap(Optional::stream)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private static Optional<RouteArrivals> selectRoute(
        ArrivalQuery query,
        List<ArrivalVisit> visits,
        ArrivalSnapshot snapshot,
        RouteArrival route,
        Instant now
    ) {
        ArrivalVisit visit = visits.stream()
            .filter(candidate -> candidate.matches(route.routeId(), route.sourceStopOrder(), snapshot.receivedAt()))
            .findFirst().orElse(null);
        String routeId = route.routeId();
        if (visit != null) {
            routeId = visit.routeId();
        }
        if (query.routeId() != null && !query.routeId().equals(routeId)) {
            return Optional.empty();
        }
        if (!matchesRequestedStop(query, visit)) {
            return Optional.empty();
        }
        return Optional.of(RouteArrivals.at(route, visit, snapshot.receivedAt(), now));
    }

    private static boolean matchesRequestedStop(ArrivalQuery query, ArrivalVisit visit) {
        if (query.stopSequence() == null) {
            return true;
        }
        return visit != null && visit.stopSequence() == query.stopSequence();
    }

    private static ArrivalAvailability availability(List<RouteArrivals> routes, ArrivalSnapshot snapshot) {
        if (routes.stream().anyMatch(route -> route.status() == ArrivalAvailability.READY)) {
            return ArrivalAvailability.READY;
        }
        if (hasUnavailableRoute(routes) || hasNoVerifiedRouteMatch(routes, snapshot)) {
            return ArrivalAvailability.UNAVAILABLE;
        }
        return ArrivalAvailability.NO_PREDICTION;
    }

    private static boolean hasUnavailableRoute(List<RouteArrivals> routes) {
        return routes.stream().anyMatch(route -> route.status() == ArrivalAvailability.UNAVAILABLE);
    }

    private static boolean hasNoVerifiedRouteMatch(List<RouteArrivals> routes, ArrivalSnapshot snapshot) {
        return routes.isEmpty() && !snapshot.routes().isEmpty();
    }
}
