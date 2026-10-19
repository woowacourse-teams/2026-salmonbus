package com.gustler.backend.api.arrival.application;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ArrivalQueryService {

    private final ArrivalReadRepository repository;
    private final ArrivalRefreshGateway gateway;
    private final ArrivalFreshnessPolicy freshness;
    private final Clock clock;
    private final ArrivalFirstResultPolicy firstResult;

    public ArrivalQueryService(ArrivalReadRepository repository, ArrivalRefreshGateway gateway,
                               ArrivalFreshnessPolicy freshness, Clock clock) {
        this(repository, gateway, freshness, clock, new ArrivalFirstResultPolicy(Duration.ZERO, 32));
    }

    @Autowired
    public ArrivalQueryService(ArrivalReadRepository repository, ArrivalRefreshGateway gateway,
        ArrivalFreshnessPolicy freshness, Clock clock, ArrivalFirstResultPolicy firstResult) {
        this.firstResult = firstResult;
        this.repository = repository;
        this.gateway = gateway;
        this.freshness = freshness;
        this.clock = clock;
    }

    // 내부 HTTP 요청 중에 API의 DB 트랜잭션/연결을 유지하지 않는다.
    public StationArrivalsOverview get(ArrivalQuery query) {
        List<ArrivalVisit> visits = repository.visits(query.stationId());
        validateTarget(query, visits);
        ArrivalSnapshot snapshot = repository.latest(query.stationId()).orElse(null);
        final boolean waiting = !freshness.usable(snapshot, clock.instant()) && firstResult.tryAcquire();
        ArrivalRefresh refresh;
        try {
            refresh = freshness.refreshDue(snapshot, clock.instant())
                ? (waiting ? gateway.request(query.stationId(), firstResult.maximumWait()) : gateway.request(query.stationId()))
                : new ArrivalRefresh(ArrivalRefresh.Status.IDLE, null, snapshot.refreshAfterAt());
            if (waiting && (refresh.status() == ArrivalRefresh.Status.ACCEPTED
                || refresh.status() == ArrivalRefresh.Status.IN_PROGRESS
                || refresh.status() == ArrivalRefresh.Status.IDLE || "NOT_DUE".equals(refresh.reason()))) {
                snapshot = repository.latest(query.stationId()).orElse(snapshot);
                if (freshness.usable(snapshot, clock.instant())) {
                    // 대기 중 바뀐 판본을 다시 확인해 새 수신을 이전 판본에 연결하지 않는다.
                    visits = repository.visits(query.stationId());
                    validateTarget(query, visits);
                    if (!freshness.refreshDue(snapshot, clock.instant())) {
                        refresh = new ArrivalRefresh(ArrivalRefresh.Status.IDLE, null, snapshot.refreshAfterAt());
                    }
                }
            }
        } finally {
            if (waiting) firstResult.release();
        }
        Instant now = clock.instant();
        if (!freshness.usable(snapshot, now)) {
            return new StationArrivalsOverview(query.stationId(), now, ArrivalAvailability.UNAVAILABLE,
                snapshot == null ? "NOT_COLLECTED" : "STALE", snapshot, refresh, List.of(), Duration.ZERO);
        }
        List<RouteArrivals> routes = selectRoutes(query, visits, snapshot, now);
        ArrivalAvailability status = availability(routes, snapshot);
        String reason = status == ArrivalAvailability.UNAVAILABLE
            ? (routes.isEmpty() ? "NO_VERIFIED_ROUTE_MATCH" : "PREDICTION_ELAPSED") : null;
        return new StationArrivalsOverview(query.stationId(), now, status, reason, snapshot, refresh, routes,
            freshness.cacheMaxAge(snapshot, now));
    }

    private static void validateTarget(ArrivalQuery query, List<ArrivalVisit> visits) {
        if (visits.isEmpty()) throw new ArrivalQuery.Failure(ErrorCode.STATION_NOT_FOUND);
        if (query.referenceVersionId() != null && visits.stream().noneMatch(visit ->
            visit.routeId().equals(query.routeId()) && visit.referenceVersionId() == query.referenceVersionId()
                && visit.stopSequence() == query.stopSequence())) {
            throw new ArrivalQuery.Failure(ErrorCode.REFERENCE_VERSION_CONFLICT);
        }
        if (query.routeId() != null && visits.stream().noneMatch(visit -> visit.routeId().equals(query.routeId()))) {
            throw new ArrivalQuery.Failure(ErrorCode.ROUTE_NOT_FOUND);
        }
    }

    private static List<RouteArrivals> selectRoutes(ArrivalQuery query, List<ArrivalVisit> visits,
                                                   ArrivalSnapshot snapshot, Instant now) {
        List<RouteArrivals> routes = new ArrayList<>();
        for (RouteArrival route : snapshot.routes()) {
            ArrivalVisit visit = visits.stream()
                .filter(candidate -> candidate.matches(route.routeId(), route.sourceStopOrder(), snapshot.receivedAt()))
                .findFirst().orElse(null);
            String routeId = visit == null ? route.routeId() : visit.routeId();
            if (query.routeId() != null && !query.routeId().equals(routeId)) continue;
            if (query.stopSequence() != null && (visit == null || visit.stopSequence() != query.stopSequence())) continue;
            routes.add(RouteArrivals.at(route, visit, snapshot.receivedAt(), now));
        }
        return routes;
    }

    private static ArrivalAvailability availability(List<RouteArrivals> routes, ArrivalSnapshot snapshot) {
        if (routes.stream().anyMatch(route -> route.status() == ArrivalAvailability.READY)) return ArrivalAvailability.READY;
        if (routes.stream().anyMatch(route -> route.status() == ArrivalAvailability.UNAVAILABLE)
            || routes.isEmpty() && !snapshot.routes().isEmpty()) return ArrivalAvailability.UNAVAILABLE;
        return ArrivalAvailability.NO_PREDICTION;
    }
}
