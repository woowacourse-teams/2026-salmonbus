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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 현재 정류장 방문 판본과 수신 결과를 조회하고 필요하면 Worker 갱신을 요청한다.
 * 내부 HTTP 대기 중 DB 트랜잭션을 유지하지 않는다.
 */
@Service
public class ArrivalQueryService {
    private final ArrivalReadRepository repository;
    private final ArrivalRefreshGateway gateway;
    private final ArrivalFreshnessPolicy freshness;
    private final Clock clock;
    private final ArrivalFirstResultPolicy firstResult;

    public ArrivalQueryService(
        ArrivalReadRepository repository,
        ArrivalRefreshGateway gateway,
        ArrivalFreshnessPolicy freshness,
        Clock clock
    ) {
        this(repository, gateway, freshness, clock, new ArrivalFirstResultPolicy(Duration.ZERO, 32));
    }

    @Autowired
    public ArrivalQueryService(
        ArrivalReadRepository repository,
        ArrivalRefreshGateway gateway,
        ArrivalFreshnessPolicy freshness,
        Clock clock,
        ArrivalFirstResultPolicy firstResult
    ) {
        this.firstResult = firstResult;
        this.repository = repository;
        this.gateway = gateway;
        this.freshness = freshness;
        this.clock = clock;
    }

    // 내부 HTTP 요청 중에 API의 DB 트랜잭션/연결을 유지하지 않는다.
    /**
     * 조회 대상을 검증하고 필요한 수집을 요청한다. 첫 유효 결과는 제한된 시간만 기다리며 사용할 결과가 없으면 UNAVAILABLE을 반환한다.
     */
    public StationArrivalsOverview get(ArrivalQuery query) {
        List<ArrivalVisit> visits = repository.visits(query.stationId());
        validateTarget(query, visits);
        ArrivalSnapshot snapshot = repository.latest(query.stationId()).orElse(null);
        final boolean waiting = !freshness.usable(snapshot, clock.instant()) && firstResult.tryAcquire();
        ArrivalRefresh refresh;
        try {
            refresh = refreshIfDue(query, snapshot, waiting);
            if (waiting && (refresh.status() == Status.ACCEPTED
                || refresh.status() == Status.IN_PROGRESS
                || refresh.status() == Status.IDLE || "NOT_DUE".equals(refresh.reason()))) {
                snapshot = repository.latest(query.stationId()).orElse(snapshot);
                if (freshness.usable(snapshot, clock.instant())) {
                    // 대기 중 바뀐 판본을 다시 확인해 새 수신을 이전 판본에 연결하지 않는다.
                    visits = repository.visits(query.stationId());
                    validateTarget(query, visits);
                    if (!freshness.refreshDue(snapshot, clock.instant())) {
                        refresh = new ArrivalRefresh(Status.IDLE, null, snapshot.refreshAfterAt());
                    }
                }
            }
        } finally {
            if (waiting) {
                firstResult.release();
            }
        }
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

    private ArrivalRefresh refreshIfDue(ArrivalQuery query, ArrivalSnapshot snapshot, final boolean waiting) {
        if (!freshness.refreshDue(snapshot, clock.instant())) {
            return new ArrivalRefresh(Status.IDLE, null, snapshot.refreshAfterAt());
        }
        if (waiting) {
            return gateway.request(query.stationId(), firstResult.maximumWait());
        }
        return gateway.request(query.stationId());
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

    private static void validateTarget(ArrivalQuery query, List<ArrivalVisit> visits) {
        if (visits.isEmpty()) {
            throw new Failure(ErrorCode.STATION_NOT_FOUND);
        }
        if (query.referenceVersionId() != null && visits.stream().noneMatch(visit ->
            visit.routeId().equals(query.routeId()) && visit.referenceVersionId() == query.referenceVersionId()
                && visit.stopSequence() == query.stopSequence())) {
            throw new Failure(ErrorCode.REFERENCE_VERSION_CONFLICT);
        }
        if (query.routeId() != null && visits.stream().noneMatch(visit -> visit.routeId().equals(query.routeId()))) {
            throw new Failure(ErrorCode.ROUTE_NOT_FOUND);
        }
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
        if (query.stopSequence() != null && (visit == null || visit.stopSequence() != query.stopSequence())) {
            return Optional.empty();
        }
        return Optional.of(RouteArrivals.at(route, visit, snapshot.receivedAt(), now));
    }

    private static ArrivalAvailability availability(List<RouteArrivals> routes, ArrivalSnapshot snapshot) {
        if (routes.stream().anyMatch(route -> route.status() == ArrivalAvailability.READY)) {
            return ArrivalAvailability.READY;
        }
        if (routes.stream().anyMatch(route -> route.status() == ArrivalAvailability.UNAVAILABLE)
                || routes.isEmpty() && !snapshot.routes().isEmpty()) {
            return ArrivalAvailability.UNAVAILABLE;
        }
        return ArrivalAvailability.NO_PREDICTION;
    }
}
